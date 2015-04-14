/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.optimizer.calcite.cost;

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistribution.Type;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Pair;
import org.apache.hadoop.hive.ql.optimizer.calcite.HiveCalciteUtil.JoinPredicateInfo;
import org.apache.hadoop.hive.ql.optimizer.calcite.reloperators.HiveAggregate;
import org.apache.hadoop.hive.ql.optimizer.calcite.reloperators.HiveJoin;
import org.apache.hadoop.hive.ql.optimizer.calcite.reloperators.HiveJoin.MapJoinStreamingRelation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

/**
 * Cost model for Tez execution engine.
 */
public class HiveOnTezCostModel extends HiveCostModel {

  public static final HiveOnTezCostModel INSTANCE =
          new HiveOnTezCostModel();

  private HiveOnTezCostModel() {
    super(Sets.newHashSet(
            TezCommonJoinAlgorithm.INSTANCE,
            TezMapJoinAlgorithm.INSTANCE,
            TezBucketJoinAlgorithm.INSTANCE,
            TezSMBJoinAlgorithm.INSTANCE));
  }

  @Override
  public RelOptCost getDefaultCost() {
    return HiveCost.FACTORY.makeZeroCost();
  }

  @Override
  public RelOptCost getAggregateCost(HiveAggregate aggregate) {
    if (aggregate.isBucketedInput()) {
      return HiveCost.FACTORY.makeZeroCost();
    } else {
      // 1. Sum of input cardinalities
      final Double rCount = RelMetadataQuery.getRowCount(aggregate.getInput());
      if (rCount == null) {
        return null;
      }
      // 2. CPU cost = sorting cost
      final double cpuCost = HiveAlgorithmsUtil.computeSortCPUCost(rCount);
      // 3. IO cost = cost of writing intermediary results to local FS +
      //              cost of reading from local FS for transferring to GBy +
      //              cost of transferring map outputs to GBy operator
      final Double rAverageSize = RelMetadataQuery.getAverageRowSize(aggregate.getInput());
      if (rAverageSize == null) {
        return null;
      }
      final double ioCost = HiveAlgorithmsUtil.computeSortIOCost(new Pair<Double,Double>(rCount,rAverageSize));
      // 4. Result
      return HiveCost.FACTORY.makeCost(rCount, cpuCost, ioCost);
    }
  }

  /**
   * COMMON_JOIN is Sort Merge Join. Each parallel computation handles multiple
   * splits.
   */
  public static class TezCommonJoinAlgorithm implements JoinAlgorithm {

    public static final JoinAlgorithm INSTANCE = new TezCommonJoinAlgorithm();
    private static final String ALGORITHM_NAME = "CommonJoin";


    @Override
    public String getName() {
      return ALGORITHM_NAME;
    }

    @Override
    public boolean isExecutable(HiveJoin join) {
      return true;
    }

    @Override
    public RelOptCost getCost(HiveJoin join) {
      // 1. Sum of input cardinalities
      final Double leftRCount = RelMetadataQuery.getRowCount(join.getLeft());
      final Double rightRCount = RelMetadataQuery.getRowCount(join.getRight());
      if (leftRCount == null || rightRCount == null) {
        return null;
      }
      final double rCount = leftRCount + rightRCount;
      // 2. CPU cost = sorting cost (for each relation) +
      //               total merge cost
      ImmutableList<Double> cardinalities = new ImmutableList.Builder<Double>().
              add(leftRCount).
              add(rightRCount).
              build();
      final double cpuCost = HiveAlgorithmsUtil.computeSortMergeCPUCost(cardinalities, join.getSortedInputs());
      // 3. IO cost = cost of writing intermediary results to local FS +
      //              cost of reading from local FS for transferring to join +
      //              cost of transferring map outputs to Join operator
      final Double leftRAverageSize = RelMetadataQuery.getAverageRowSize(join.getLeft());
      final Double rightRAverageSize = RelMetadataQuery.getAverageRowSize(join.getRight());
      if (leftRAverageSize == null || rightRAverageSize == null) {
        return null;
      }
      ImmutableList<Pair<Double,Double>> relationInfos = new ImmutableList.Builder<Pair<Double,Double>>().
              add(new Pair<Double,Double>(leftRCount,leftRAverageSize)).
              add(new Pair<Double,Double>(rightRCount,rightRAverageSize)).
              build();
      final double ioCost = HiveAlgorithmsUtil.computeSortMergeIOCost(relationInfos);
      // 4. Result
      return HiveCost.FACTORY.makeCost(rCount, cpuCost, ioCost);
    }

    @Override
    public ImmutableList<RelCollation> getCollation(HiveJoin join) {
      return HiveAlgorithmsUtil.getJoinCollation(join.getJoinPredicateInfo(),
              MapJoinStreamingRelation.NONE);
    }

    @Override
    public RelDistribution getDistribution(HiveJoin join) {
      return HiveAlgorithmsUtil.getJoinRedistribution(join.getJoinPredicateInfo());
    }

    @Override
    public Double getMemory(HiveJoin join) {
      return HiveAlgorithmsUtil.getJoinMemory(join, MapJoinStreamingRelation.NONE);
    }

    @Override
    public Double getCumulativeMemoryWithinPhaseSplit(HiveJoin join) {
      final Double memoryWithinPhase =
          RelMetadataQuery.cumulativeMemoryWithinPhase(join);
      final Integer splitCount = RelMetadataQuery.splitCount(join);
      if (memoryWithinPhase == null || splitCount == null) {
        return null;
      }
      return memoryWithinPhase / splitCount;
    }

    @Override
    public Boolean isPhaseTransition(HiveJoin join) {
      return true;
    }

    @Override
    public Integer getSplitCount(HiveJoin join) {
      return HiveAlgorithmsUtil.getSplitCountWithRepartition(join);
    }
  }

  /**
   * MAP_JOIN a hash join that keeps the whole data set of non streaming tables
   * in memory.
   */
  public static class TezMapJoinAlgorithm implements JoinAlgorithm {

    public static final JoinAlgorithm INSTANCE = new TezMapJoinAlgorithm();
    private static final String ALGORITHM_NAME = "MapJoin";


    @Override
    public String getName() {
      return ALGORITHM_NAME;
    }

    @Override
    public boolean isExecutable(HiveJoin join) {
      final Double maxMemory = join.getCluster().getPlanner().getContext().
              unwrap(HiveAlgorithmsConf.class).getMaxMemory();
      // Check streaming side
      RelNode smallInput = join.getStreamingInput();
      if (smallInput == null) {
        return false;
      }
      return HiveAlgorithmsUtil.isFittingIntoMemory(maxMemory, smallInput, 1);
    }

    @Override
    public RelOptCost getCost(HiveJoin join) {
      // 1. Sum of input cardinalities
      final Double leftRCount = RelMetadataQuery.getRowCount(join.getLeft());
      final Double rightRCount = RelMetadataQuery.getRowCount(join.getRight());
      if (leftRCount == null || rightRCount == null) {
        return null;
      }
      final double rCount = leftRCount + rightRCount;
      // 2. CPU cost = HashTable  construction  cost  +
      //               join cost
      ImmutableList<Double> cardinalities = new ImmutableList.Builder<Double>().
              add(leftRCount).
              add(rightRCount).
              build();
      ImmutableBitSet.Builder streamingBuilder = new ImmutableBitSet.Builder();
      switch (join.getStreamingSide()) {
        case LEFT_RELATION:
          streamingBuilder.set(0);
          break;
        case RIGHT_RELATION:
          streamingBuilder.set(1);
          break;
        default:
          return null;
      }
      ImmutableBitSet streaming = streamingBuilder.build();
      final double cpuCost = HiveAlgorithmsUtil.computeMapJoinCPUCost(cardinalities, streaming);
      // 3. IO cost = cost of transferring small tables to join node *
      //              degree of parallelism
      final Double leftRAverageSize = RelMetadataQuery.getAverageRowSize(join.getLeft());
      final Double rightRAverageSize = RelMetadataQuery.getAverageRowSize(join.getRight());
      if (leftRAverageSize == null || rightRAverageSize == null) {
        return null;
      }
      ImmutableList<Pair<Double,Double>> relationInfos = new ImmutableList.Builder<Pair<Double,Double>>().
              add(new Pair<Double,Double>(leftRCount,leftRAverageSize)).
              add(new Pair<Double,Double>(rightRCount,rightRAverageSize)).
              build();
      final int parallelism = RelMetadataQuery.splitCount(join) == null
              ? 1 : RelMetadataQuery.splitCount(join);
      final double ioCost = HiveAlgorithmsUtil.computeMapJoinIOCost(relationInfos, streaming, parallelism);
      // 4. Result
      return HiveCost.FACTORY.makeCost(rCount, cpuCost, ioCost);
    }

    @Override
    public ImmutableList<RelCollation> getCollation(HiveJoin join) {
      if (join.getStreamingSide() != MapJoinStreamingRelation.LEFT_RELATION
              || join.getStreamingSide() != MapJoinStreamingRelation.RIGHT_RELATION) {
        return null;
      }
      return HiveAlgorithmsUtil.getJoinCollation(join.getJoinPredicateInfo(),
              join.getStreamingSide());
    }

    @Override
    public RelDistribution getDistribution(HiveJoin join) {
      if (join.getStreamingSide() != MapJoinStreamingRelation.LEFT_RELATION
              || join.getStreamingSide() != MapJoinStreamingRelation.RIGHT_RELATION) {
        return null;
      }      
      return HiveAlgorithmsUtil.getJoinDistribution(join.getJoinPredicateInfo(),
              join.getStreamingSide());
    }

    @Override
    public Double getMemory(HiveJoin join) {
      return HiveAlgorithmsUtil.getJoinMemory(join);
    }

    @Override
    public Double getCumulativeMemoryWithinPhaseSplit(HiveJoin join) {
      // Check streaming side
      RelNode inMemoryInput;
      if (join.getStreamingSide() == MapJoinStreamingRelation.LEFT_RELATION) {
        inMemoryInput = join.getRight();
      } else if (join.getStreamingSide() == MapJoinStreamingRelation.RIGHT_RELATION) {
        inMemoryInput = join.getLeft();
      } else {
        return null;
      }
      // If simple map join, the whole relation goes in memory
      return RelMetadataQuery.cumulativeMemoryWithinPhase(inMemoryInput);
    }

    @Override
    public Boolean isPhaseTransition(HiveJoin join) {
      return false;
    }

    @Override
    public Integer getSplitCount(HiveJoin join) {
      return HiveAlgorithmsUtil.getSplitCountWithoutRepartition(join);
    }
  }

  /**
   * BUCKET_JOIN is a hash joins where one bucket of the non streaming tables
   * is kept in memory at the time.
   */
  public static class TezBucketJoinAlgorithm implements JoinAlgorithm {

    public static final JoinAlgorithm INSTANCE = new TezBucketJoinAlgorithm();
    private static final String ALGORITHM_NAME = "BucketJoin";


    @Override
    public String getName() {
      return ALGORITHM_NAME;
    }

    @Override
    public boolean isExecutable(HiveJoin join) {
      final Double maxMemory = join.getCluster().getPlanner().getContext().
              unwrap(HiveAlgorithmsConf.class).getMaxMemory();
      // Check streaming side
      RelNode smallInput = join.getStreamingInput();
      if (smallInput == null) {
        return false;
      }
      // Get key columns
      JoinPredicateInfo joinPredInfo = join.getJoinPredicateInfo();
      List<ImmutableIntList> joinKeysInChildren = new ArrayList<ImmutableIntList>();
      joinKeysInChildren.add(
              ImmutableIntList.copyOf(
                      joinPredInfo.getProjsFromLeftPartOfJoinKeysInChildSchema()));
      joinKeysInChildren.add(
              ImmutableIntList.copyOf(
                      joinPredInfo.getProjsFromRightPartOfJoinKeysInChildSchema()));

      // Requirements: for Bucket, bucketed by their keys on both sides and fitting in memory
      // Obtain number of buckets
      Integer buckets = RelMetadataQuery.splitCount(smallInput);
      if (buckets == null) {
        return false;
      }
      if (!HiveAlgorithmsUtil.isFittingIntoMemory(maxMemory, smallInput, buckets)) {
        return false;
      }
      for (int i=0; i<join.getInputs().size(); i++) {
        RelNode input = join.getInputs().get(i);
        // Is bucketJoin possible? We need correct bucketing
        RelDistribution distribution = RelMetadataQuery.distribution(input);
        if (distribution.getType() != Type.HASH_DISTRIBUTED) {
          return false;
        }
        if (!distribution.getKeys().containsAll(joinKeysInChildren.get(i))) {
          return false;
        }
      }
      return true;
    }

    @Override
    public RelOptCost getCost(HiveJoin join) {
      // 1. Sum of input cardinalities
      final Double leftRCount = RelMetadataQuery.getRowCount(join.getLeft());
      final Double rightRCount = RelMetadataQuery.getRowCount(join.getRight());
      if (leftRCount == null || rightRCount == null) {
        return null;
      }
      final double rCount = leftRCount + rightRCount;
      // 2. CPU cost = HashTable  construction  cost  +
      //               join cost
      ImmutableList<Double> cardinalities = new ImmutableList.Builder<Double>().
              add(leftRCount).
              add(rightRCount).
              build();
      ImmutableBitSet.Builder streamingBuilder = new ImmutableBitSet.Builder();
      switch (join.getStreamingSide()) {
        case LEFT_RELATION:
          streamingBuilder.set(0);
          break;
        case RIGHT_RELATION:
          streamingBuilder.set(1);
          break;
        default:
          return null;
      }
      ImmutableBitSet streaming = streamingBuilder.build();
      final double cpuCost = HiveAlgorithmsUtil.computeBucketMapJoinCPUCost(cardinalities, streaming);
      // 3. IO cost = cost of transferring small tables to join node *
      //              degree of parallelism
      final Double leftRAverageSize = RelMetadataQuery.getAverageRowSize(join.getLeft());
      final Double rightRAverageSize = RelMetadataQuery.getAverageRowSize(join.getRight());
      if (leftRAverageSize == null || rightRAverageSize == null) {
        return null;
      }
      ImmutableList<Pair<Double,Double>> relationInfos = new ImmutableList.Builder<Pair<Double,Double>>().
              add(new Pair<Double,Double>(leftRCount,leftRAverageSize)).
              add(new Pair<Double,Double>(rightRCount,rightRAverageSize)).
              build();
      final int parallelism = RelMetadataQuery.splitCount(join) == null
              ? 1 : RelMetadataQuery.splitCount(join);
      final double ioCost = HiveAlgorithmsUtil.computeBucketMapJoinIOCost(relationInfos, streaming, parallelism);
      // 4. Result
      return HiveCost.FACTORY.makeCost(rCount, cpuCost, ioCost);
    }

    @Override
    public ImmutableList<RelCollation> getCollation(HiveJoin join) {
      if (join.getStreamingSide() != MapJoinStreamingRelation.LEFT_RELATION
              || join.getStreamingSide() != MapJoinStreamingRelation.RIGHT_RELATION) {
        return null;
      }
      return HiveAlgorithmsUtil.getJoinCollation(join.getJoinPredicateInfo(),
              join.getStreamingSide());
    }

    @Override
    public RelDistribution getDistribution(HiveJoin join) {
      return HiveAlgorithmsUtil.getJoinRedistribution(join.getJoinPredicateInfo());
    }

    @Override
    public Double getMemory(HiveJoin join) {
      return HiveAlgorithmsUtil.getJoinMemory(join);
    }

    @Override
    public Double getCumulativeMemoryWithinPhaseSplit(HiveJoin join) {
      // Check streaming side
      RelNode inMemoryInput;
      if (join.getStreamingSide() == MapJoinStreamingRelation.LEFT_RELATION) {
        inMemoryInput = join.getRight();
      } else if (join.getStreamingSide() == MapJoinStreamingRelation.RIGHT_RELATION) {
        inMemoryInput = join.getLeft();
      } else {
        return null;
      }
      // If bucket map join, only a split goes in memory
      final Double memoryInput =
              RelMetadataQuery.cumulativeMemoryWithinPhase(inMemoryInput);
      final Integer splitCount = RelMetadataQuery.splitCount(inMemoryInput);
      if (memoryInput == null || splitCount == null) {
        return null;
      }
      return memoryInput / splitCount;
    }

    @Override
    public Boolean isPhaseTransition(HiveJoin join) {
      return false;
    }

    @Override
    public Integer getSplitCount(HiveJoin join) {
      return HiveAlgorithmsUtil.getSplitCountWithoutRepartition(join);
    }
  }

  /**
   * SMB_JOIN is a Sort Merge Join. Each parallel computation handles one bucket.
   */
  public static class TezSMBJoinAlgorithm implements JoinAlgorithm {

    public static final JoinAlgorithm INSTANCE = new TezSMBJoinAlgorithm();
    private static final String ALGORITHM_NAME = "SMBJoin";


    @Override
    public String getName() {
      return ALGORITHM_NAME;
    }

    @Override
    public boolean isExecutable(HiveJoin join) {
      // Requirements: for SMB, sorted by their keys on both sides and bucketed.
      // Get key columns
      JoinPredicateInfo joinPredInfo = join.getJoinPredicateInfo();
      List<ImmutableIntList> joinKeysInChildren = new ArrayList<ImmutableIntList>();
      joinKeysInChildren.add(
              ImmutableIntList.copyOf(
                      joinPredInfo.getProjsFromLeftPartOfJoinKeysInChildSchema()));
      joinKeysInChildren.add(
              ImmutableIntList.copyOf(
                      joinPredInfo.getProjsFromRightPartOfJoinKeysInChildSchema()));

      for (int i=0; i<join.getInputs().size(); i++) {
        RelNode input = join.getInputs().get(i);
        // Is smbJoin possible? We need correct order
        boolean orderFound = join.getSortedInputs().get(i);
        if (!orderFound) {
          return false;
        }
        // Is smbJoin possible? We need correct bucketing
        RelDistribution distribution = RelMetadataQuery.distribution(input);
        if (distribution.getType() != Type.HASH_DISTRIBUTED) {
          return false;
        }
        if (!distribution.getKeys().containsAll(joinKeysInChildren.get(i))) {
          return false;
        }
      }
      return true;
    }

    @Override
    public RelOptCost getCost(HiveJoin join) {
      // 1. Sum of input cardinalities
      final Double leftRCount = RelMetadataQuery.getRowCount(join.getLeft());
      final Double rightRCount = RelMetadataQuery.getRowCount(join.getRight());
      if (leftRCount == null || rightRCount == null) {
        return null;
      }
      final double rCount = leftRCount + rightRCount;
      // 2. CPU cost = HashTable  construction  cost  +
      //               join cost
      ImmutableList<Double> cardinalities = new ImmutableList.Builder<Double>().
              add(leftRCount).
              add(rightRCount).
              build();
      ImmutableBitSet.Builder streamingBuilder = new ImmutableBitSet.Builder();
      switch (join.getStreamingSide()) {
        case LEFT_RELATION:
          streamingBuilder.set(0);
          break;
        case RIGHT_RELATION:
          streamingBuilder.set(1);
          break;
        default:
          return null;
      }
      ImmutableBitSet streaming = streamingBuilder.build();
      final double cpuCost = HiveAlgorithmsUtil.computeSMBMapJoinCPUCost(cardinalities);
      // 3. IO cost = cost of transferring small tables to join node *
      //              degree of parallelism
      final Double leftRAverageSize = RelMetadataQuery.getAverageRowSize(join.getLeft());
      final Double rightRAverageSize = RelMetadataQuery.getAverageRowSize(join.getRight());
      if (leftRAverageSize == null || rightRAverageSize == null) {
        return null;
      }
      ImmutableList<Pair<Double,Double>> relationInfos = new ImmutableList.Builder<Pair<Double,Double>>().
              add(new Pair<Double,Double>(leftRCount,leftRAverageSize)).
              add(new Pair<Double,Double>(rightRCount,rightRAverageSize)).
              build();
      final int parallelism = RelMetadataQuery.splitCount(join) == null
              ? 1 : RelMetadataQuery.splitCount(join);
      final double ioCost = HiveAlgorithmsUtil.computeSMBMapJoinIOCost(relationInfos, streaming, parallelism);
      // 4. Result
      return HiveCost.FACTORY.makeCost(rCount, cpuCost, ioCost);
    }

    @Override
    public ImmutableList<RelCollation> getCollation(HiveJoin join) {
      return HiveAlgorithmsUtil.getJoinCollation(join.getJoinPredicateInfo(),
              MapJoinStreamingRelation.NONE);
    }

    @Override
    public RelDistribution getDistribution(HiveJoin join) {
      return HiveAlgorithmsUtil.getJoinRedistribution(join.getJoinPredicateInfo());
    }

    @Override
    public Double getMemory(HiveJoin join) {
      return 0.0;
    }

    @Override
    public Double getCumulativeMemoryWithinPhaseSplit(HiveJoin join) {
      final Double memoryWithinPhase =
          RelMetadataQuery.cumulativeMemoryWithinPhase(join);
      final Integer splitCount = RelMetadataQuery.splitCount(join);
      if (memoryWithinPhase == null || splitCount == null) {
        return null;
      }
      return memoryWithinPhase / splitCount;
    }

    @Override
    public Boolean isPhaseTransition(HiveJoin join) {
      return false;
    }

    @Override
    public Integer getSplitCount(HiveJoin join) {
      return HiveAlgorithmsUtil.getSplitCountWithoutRepartition(join);
    }
  }

}