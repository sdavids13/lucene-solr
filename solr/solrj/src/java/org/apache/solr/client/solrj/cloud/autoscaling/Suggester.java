/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.client.solrj.cloud.autoscaling;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.impl.ClusterStateProvider;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.rule.ImplicitSnitch;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.util.Pair;
import org.apache.solr.common.util.Utils;

import static org.apache.solr.client.solrj.cloud.autoscaling.Suggestion.ConditionType.FREEDISK;

/* A suggester is capable of suggesting a collection operation
 * given a particular session. Before it suggests a new operation,
 * it ensures that ,
 *  a) load is reduced on the most loaded node
 *  b) it causes no new violations
 *
 */
public abstract class Suggester implements MapWriter {
  protected final EnumMap<Hint, Object> hints = new EnumMap<>(Hint.class);
  Policy.Session session;
  SolrRequest operation;
  boolean force;
  protected List<Violation> originalViolations = new ArrayList<>();
  private boolean isInitialized = false;

  void _init(Policy.Session session) {
    this.session = session.copy();
  }

  public Suggester hint(Hint hint, Object value) {
    hint.validator.accept(value);
    if (hint.multiValued) {
      Collection<?> values = value instanceof Collection ? (Collection) value : Collections.singletonList(value);
      ((Set) hints.computeIfAbsent(hint, h -> new HashSet<>())).addAll(values);
    } else {
      hints.put(hint, value == null ? null : String.valueOf(value));
    }
    return this;
  }

  public CollectionParams.CollectionAction getAction() {
    return null;
  }

  /**
   * Normally, only less loaded nodes are used for moving replicas. If this is a violation and a MOVE must be performed,
   * set the flag to true.
   */
  public Suggester forceOperation(boolean force) {
    this.force = force;
    return this;
  }

  protected boolean isNodeSuitableForReplicaAddition(Row row) {
    if (!row.isLive) return false;
    if (!isAllowed(row.node, Hint.TARGET_NODE)) return false;
    if (!isAllowed(row.getVal(ImplicitSnitch.DISK), Hint.MINFREEDISK)) return false;
    return true;
  }

  abstract SolrRequest init();


  public SolrRequest getSuggestion() {
    if (!isInitialized) {
      Set<String> collections = (Set<String>) hints.getOrDefault(Hint.COLL, Collections.emptySet());
      Set<Pair<String, String>> s = (Set<Pair<String, String>>) hints.getOrDefault(Hint.COLL_SHARD, Collections.emptySet());
      if (!collections.isEmpty() || !s.isEmpty()) {
        HashSet<Pair<String, String>> shards = new HashSet<>(s);
        collections.stream().forEach(c -> shards.add(new Pair<>(c, null)));
        ClusterStateProvider stateProvider = session.cloudManager.getClusterStateProvider();
        for (Pair<String, String> shard : shards) {
          // if this is not a known collection from the existing clusterstate,
          // then add it
          if (session.matrix.stream().noneMatch(row -> row.collectionVsShardVsReplicas.containsKey(shard.first()))) {
            session.addClausesForCollection(stateProvider, shard.first());
          }
          for (Row row : session.matrix) {
            Map<String, List<ReplicaInfo>> shardInfo = row.collectionVsShardVsReplicas.computeIfAbsent(shard.first(), it -> new HashMap<>());
            if (shard.second() != null) shardInfo.computeIfAbsent(shard.second(), it -> new ArrayList<>());
          }
        }
        Collections.sort(session.expandedClauses);
      }
      Set<String> srcNodes = (Set<String>) hints.get(Hint.SRC_NODE);
      if (srcNodes != null && !srcNodes.isEmpty()) {
        // the source node is dead so live nodes may not have it
        for (String srcNode : srcNodes) {
          if (session.matrix.stream().noneMatch(row -> row.node.equals(srcNode)))
            session.matrix.add(new Row(srcNode, session.getPolicy().params, session.getPolicy().perReplicaAttributes, session));
        }
      }
      session.applyRules();
      originalViolations.addAll(session.getViolations());
      this.operation = init();
      isInitialized = true;
    }
    if (operation != null && session.transaction != null && session.transaction.isOpen()) {
      session.transaction.updateSession(session);
    }
    return operation;
  }

  public Policy.Session getSession() {
    return session;
  }

  List<Row> getMatrix() {
    return session.matrix;

  }

  public static class SuggestionInfo implements MapWriter {
    Violation violation;
    SolrRequest operation;

    public SuggestionInfo(Violation violation, SolrRequest op) {
      this.violation = violation;
      this.operation = op;
    }

    public SolrRequest getOperation() {
      return operation;
    }

    public Violation getViolation() {
      return violation;
    }

    @Override
    public void writeMap(EntryWriter ew) throws IOException {
      ew.put("type", violation == null ? "improvement" : "violation");
      ew.putIfNotNull("violation", violation);
      ew.put("operation", operation);
    }

    @Override
    public String toString() {
      return Utils.toJSONString(this);
    }
  }

  //check if the fresh set of violations is less serious than the last set of violations
  boolean isLessSerious(List<Violation> fresh, List<Violation> old) {
    if (old == null || fresh.size() < old.size()) return true;
    if (fresh.size() == old.size()) {
      for (int i = 0; i < fresh.size(); i++) {
        Violation freshViolation = fresh.get(i);
        Violation oldViolation = null;
        for (Violation v : old) {//look for exactly same clause being violated
          if (v.equals(freshViolation)) oldViolation = v;
        }
        if (oldViolation == null) {//if no match, look for similar violation
          for (Violation v : old) {
            if (v.isSimilarViolation(freshViolation)) oldViolation = v;
          }
        }

        if (oldViolation != null && freshViolation.isLessSerious(oldViolation)) return true;
      }
    }
    return false;
  }

  boolean containsNewErrors(List<Violation> violations) {
    boolean isTxOpen = session.transaction != null && session.transaction.isOpen();
    for (Violation v : violations) {
      //the computed value can change over time. So it's better to evaluate it in the end
      if (isTxOpen && v.getClause().hasComputedValue) continue;
      int idx = originalViolations.indexOf(v);
      if (idx < 0 /*|| originalViolations.get(idx).isLessSerious(v)*/) return true;
    }
    return false;
  }

  List<Pair<ReplicaInfo, Row>> getValidReplicas(boolean sortDesc, boolean isSource, int until) {
    List<Pair<ReplicaInfo, Row>> allPossibleReplicas = new ArrayList<>();

    if (sortDesc) {
      if (until == -1) until = getMatrix().size();
      for (int i = 0; i < until; i++) addReplicaToList(getMatrix().get(i), isSource, allPossibleReplicas);
    } else {
      if (until == -1) until = 0;
      for (int i = getMatrix().size() - 1; i >= until; i--)
        addReplicaToList(getMatrix().get(i), isSource, allPossibleReplicas);
    }
    return allPossibleReplicas;
  }

  void addReplicaToList(Row r, boolean isSource, List<Pair<ReplicaInfo, Row>> replicaList) {
    if (!isAllowed(r.node, isSource ? Hint.SRC_NODE : Hint.TARGET_NODE)) return;
    for (Map.Entry<String, Map<String, List<ReplicaInfo>>> e : r.collectionVsShardVsReplicas.entrySet()) {
      if (!isAllowed(e.getKey(), Hint.COLL)) continue;
      for (Map.Entry<String, List<ReplicaInfo>> shard : e.getValue().entrySet()) {
        if (!isAllowed(new Pair<>(e.getKey(), shard.getKey()), Hint.COLL_SHARD)) continue;//todo fix
        if (shard.getValue() == null || shard.getValue().isEmpty()) continue;
        replicaList.add(new Pair<>(shard.getValue().get(0), r));
      }
    }
  }

  List<Violation> testChangedMatrix(boolean strict, Policy.Session session) {
    Policy.setApproxValuesAndSortNodes(session.getPolicy().clusterPreferences, session.matrix);
    List<Violation> errors = new ArrayList<>();
    for (Clause clause : session.expandedClauses) {
      if (strict || clause.strict) {
        List<Violation> errs = clause.test(session);
        if (!errs.isEmpty()) {
          errors.addAll(errs);
        }
      }
    }
    return errors;
  }


  protected boolean isAllowed(Object v, Hint hint) {
    Object hintVal = hints.get(hint);
    if (hintVal == null) return true;
    if (hint.multiValued) {
      Set set = (Set) hintVal;
      return set == null || set.contains(v);
    } else {
      return hintVal == null || hint.valueValidator.test(new Pair<>(hintVal, v));
    }
  }

  public enum Hint {
    COLL(true),
    // collection shard pair
    // this should be a Pair<String, String> , (collection,shard)
    COLL_SHARD(true, v -> {
      Collection c = v instanceof Collection ? (Collection) v : Collections.singleton(v);
      for (Object o : c) {
        if (!(o instanceof Pair)) {
          throw new RuntimeException("COLL_SHARD hint must use a Pair");
        }
        Pair p = (Pair) o;
        if (p.first() == null || p.second() == null) {
          throw new RuntimeException("Both collection and shard must not be null");
        }
      }

    }) {
      @Override
      public Object parse(Object v) {
        if (v instanceof Map) {
          Map map = (Map) v;
          return Pair.parse(map);
        }
        return super.parse(v);
      }
    },
    SRC_NODE(true),
    TARGET_NODE(true),
    REPLICATYPE(false, o -> {
      if (!(o instanceof Replica.Type)) {
        throw new RuntimeException("REPLICATYPE hint must use a ReplicaType");
      }
    }),

    MINFREEDISK(false, o -> {
      if (!(o instanceof Number)) throw new RuntimeException("MINFREEDISK hint must be a number");
    }, hintValVsActual -> {
      Double hintFreediskInGb = (Double) FREEDISK.validate(null, hintValVsActual.first(), false);
      Double actualFreediskInGb = (Double) FREEDISK.validate(null, hintValVsActual.second(), false);
      if (actualFreediskInGb == null) return false;
      return actualFreediskInGb > hintFreediskInGb;
    }),
    NUMBER(true, o -> {
      if (!(o instanceof Number)) throw new RuntimeException("NUMBER hint must be a number");
    }),
    REPLICA(true);

    public final boolean multiValued;
    public final Consumer<Object> validator;
    public final Predicate<Pair<Object, Object>> valueValidator;

    Hint(boolean multiValued) {
      this(multiValued, v -> {
        Collection c = v instanceof Collection ? (Collection) v : Collections.singleton(v);
        for (Object o : c) {
          if (!(o instanceof String)) throw new RuntimeException("hint must be of type String");
        }
      });
    }

    Hint(boolean multiValued, Consumer<Object> c) {
      this(multiValued, c, equalsPredicate);
    }

    Hint(boolean multiValued, Consumer<Object> c, Predicate<Pair<Object, Object>> testval) {
      this.multiValued = multiValued;
      this.validator = c;
      this.valueValidator = testval;
    }

    public static Hint get(String s) {
      for (Hint hint : values()) {
        if (hint.name().equals(s)) return hint;
      }
      return null;
    }

    public Object parse(Object v) {
      return v;
    }


  }

  static Predicate<Pair<Object, Object>> equalsPredicate = valPair -> Objects.equals(valPair.first(), valPair.second());

  @Override
  public String toString() {
    return jsonStr();
  }

  @Override
  public void writeMap(EntryWriter ew) throws IOException {
    ew.put("action", String.valueOf(getAction()));
    ew.put("hints", (MapWriter) ew1 -> hints.forEach((hint, o) -> ew1.putNoEx(hint.toString(), o)));
  }
}
