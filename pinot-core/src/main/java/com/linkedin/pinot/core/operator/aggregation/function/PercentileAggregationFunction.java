/**
 * Copyright (C) 2014-2016 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.core.operator.aggregation.function;

import com.google.common.base.Preconditions;
import com.linkedin.pinot.core.operator.aggregation.AggregationResultHolder;
import com.linkedin.pinot.core.operator.aggregation.groupby.GroupByResultHolder;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.List;


/**
 * Class to implement the 'percentileXX' aggregation function.
 */
public class PercentileAggregationFunction implements AggregationFunction {
  private final String FUNCTION_NAME;
  private static final ResultDataType RESULT_DATA_TYPE = ResultDataType.PERCENTILE_LIST;
  private final int _percentile;

  public PercentileAggregationFunction(int percentile) {
    switch (percentile) {
      case 50:
        FUNCTION_NAME = AggregationFunctionFactory.PERCENTILE50_AGGREGATION_FUNCTION;
        break;
      case 90:
        FUNCTION_NAME = AggregationFunctionFactory.PERCENTILE90_AGGREGATION_FUNCTION;
        break;
      case 95:
        FUNCTION_NAME = AggregationFunctionFactory.PERCENTILE95_AGGREGATION_FUNCTION;
        break;
      case 99:
        FUNCTION_NAME = AggregationFunctionFactory.PERCENTILE99_AGGREGATION_FUNCTION;
        break;
      default:
        throw new RuntimeException("Invalid percentile for PercentileAggregationFunction: " + percentile);
    }
    _percentile = percentile;
  }

  /**
   * Performs 'percentile' aggregation on the input array.
   *
   * {@inheritDoc}
   *
   * @param length
   * @param resultHolder
   * @param valueArray
   */
  @Override
  public void aggregate(int length, AggregationResultHolder resultHolder, double[]... valueArray) {
    Preconditions.checkArgument(valueArray.length == 1);
    Preconditions.checkState(length <= valueArray[0].length);

    DoubleArrayList valueList = resultHolder.getResult();
    if (valueList == null) {
      valueList = new DoubleArrayList();
      resultHolder.setValue(valueList);
    }

    for (int i = 0; i < length; i++) {
      valueList.add(valueArray[0][i]);
    }
  }

  /**
   * {@inheritDoc}
   *
   * While the interface allows for variable number of valueArrays, we do not support
   * multiple columns within one aggregation function right now.
   *
   * @param length
   * @param groupKeys
   * @param resultHolder
   * @param valueArray
   */
  @Override
  public void aggregateGroupBySV(int length, int[] groupKeys, GroupByResultHolder resultHolder,
      double[]... valueArray) {
    Preconditions.checkArgument(valueArray.length == 1);
    Preconditions.checkState(length <= valueArray[0].length);

    for (int i = 0; i < length; i++) {
      int groupKey = groupKeys[i];
      DoubleArrayList valueList = resultHolder.getResult(groupKey);
      if (valueList == null) {
        valueList = new DoubleArrayList();
        resultHolder.setValueForKey(groupKey, valueList);
      }
      valueList.add(valueArray[0][i]);
    }
  }

  /**
   * {@inheritDoc}
   *
   * @param length
   * @param docIdToGroupKeys
   * @param resultHolder
   * @param valueArray
   */
  @Override
  public void aggregateGroupByMV(int length, int[][] docIdToGroupKeys, GroupByResultHolder resultHolder,
      double[]... valueArray) {
    Preconditions.checkArgument(valueArray.length == 1);
    Preconditions.checkState(length <= valueArray[0].length);

    for (int i = 0; i < length; ++i) {
      double value = valueArray[0][i];
      for (int groupKey : docIdToGroupKeys[i]) {
        DoubleArrayList valueList = resultHolder.getResult(groupKey);
        if (valueList == null) {
          valueList = new DoubleArrayList();
          resultHolder.setValueForKey(groupKey, valueList);
        }
        valueList.add(value);
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * @return
   */
  @Override
  public double getDefaultValue() {
    throw new RuntimeException("Unsupported method getDefaultValue() for class " + getClass().getName());
  }

  /**
   * {@inheritDoc}
   * @return
   */
  @Override
  public ResultDataType getResultDataType() {
    return RESULT_DATA_TYPE;
  }

  @Override
  public String getName() {
    return FUNCTION_NAME;
  }

  /**
   * {@inheritDoc}
   *
   * @param combinedResult
   * @return
   */
  @Override
  public Double reduce(List<Object> combinedResult) {
    throw new RuntimeException(
        "Unsupported method reduce(List<Object> combinedResult) for class " + getClass().getName());
  }
}
