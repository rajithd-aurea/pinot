package com.linkedin.pinot.core.block.aggregation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import com.linkedin.pinot.common.response.ProcessingException;
import com.linkedin.pinot.common.response.ResponseStatistics;
import com.linkedin.pinot.common.response.RowEvent;
import com.linkedin.pinot.common.utils.DataTable;
import com.linkedin.pinot.common.utils.DataTableBuilder;
import com.linkedin.pinot.common.utils.DataTableBuilder.DataSchema;
import com.linkedin.pinot.core.common.Block;
import com.linkedin.pinot.core.common.BlockDocIdSet;
import com.linkedin.pinot.core.common.BlockDocIdValueSet;
import com.linkedin.pinot.core.common.BlockId;
import com.linkedin.pinot.core.common.BlockMetadata;
import com.linkedin.pinot.core.common.BlockValSet;
import com.linkedin.pinot.core.common.Predicate;
import com.linkedin.pinot.core.query.aggregation.AggregationFunction;
import com.linkedin.pinot.core.query.aggregation.AggregationService;
import com.linkedin.pinot.core.query.aggregation.groupby.GroupByAggregationService;
import com.linkedin.pinot.core.query.selection.SelectionService;


/**
 * A holder of InstanceResponse components. Easy to do merge.
 * 
 * @author xiafu
 *
 */
public class IntermediateResultsBlock implements Block {
  private DataTable _dataTable;
  private List<AggregationFunction> _aggregationFunctionList;
  private List<Serializable> _aggregationResultList;
  private List<ProcessingException> _processingExceptions;
  private long _numDocsScanned;
  private long _requestId = -1;
  private List<RowEvent> _rowEvents;
  private List<ResponseStatistics> _segmentStatistics;
  private long _timeUsedMs;
  private long _totalDocs;
  private Map<String, String> _traceInfo;
  private HashMap<String, List<Serializable>> _aggregationGroupByResult;
  private DataSchema _dataSchema;
  private PriorityQueue<Serializable[]> _selectionResult;

  private static String REQUEST_ID = "requestId";
  private static String NUM_DOCS_SCANNED = "numDocsScanned";
  private static String TIME_USED_MS = "timeUsedMs";
  private static String TOTAL_DOCS = "totalDocs";

  public IntermediateResultsBlock(List<AggregationFunction> aggregationFunctionList,
      List<Serializable> aggregationResult) {
    _aggregationFunctionList = aggregationFunctionList;
    _aggregationResultList = aggregationResult;
  }

  public IntermediateResultsBlock(List<AggregationFunction> aggregationFunctionList,
      HashMap<String, List<Serializable>> aggregationGroupByResult) {
    _aggregationFunctionList = aggregationFunctionList;
    _aggregationGroupByResult = aggregationGroupByResult;
  }

  public IntermediateResultsBlock(Exception e) {
    if (_processingExceptions == null) {
      _processingExceptions = new ArrayList<ProcessingException>();
    }
  }

  public IntermediateResultsBlock() {
    // TODO Auto-generated constructor stub
  }

  @Override
  public boolean applyPredicate(Predicate predicate) {
    throw new UnsupportedOperationException();
  }

  @Override
  public BlockId getId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BlockValSet getBlockValueSet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BlockDocIdValueSet getBlockDocIdValueSet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BlockDocIdSet getBlockDocIdSet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BlockMetadata getMetadata() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getIntValue(int docId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public float getFloatValue(int docId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void resetBlock() {
    throw new UnsupportedOperationException();
  }

  public List<Serializable> getAggregationResult() {
    return _aggregationResultList;
  }

  public DataTable getDataTable() throws Exception {
    if (_aggregationResultList != null) {
      return getAggregationResultDataTable();
    }
    if (_aggregationGroupByResult != null) {
      return getAggregationGroupByResultDataTable();
    }
    if (_selectionResult != null) {
      return getSelectionResultDataTable();
    }
    throw new UnsupportedOperationException("Cannot get DataTable from IntermediateResultBlock!");
  }

  public DataTable attachMetadataToDataTable(DataTable dataTable) {
    dataTable.getMetadata().put(REQUEST_ID, _requestId + "");
    dataTable.getMetadata().put(NUM_DOCS_SCANNED, _numDocsScanned + "");
    dataTable.getMetadata().put(TIME_USED_MS, _timeUsedMs + "");
    dataTable.getMetadata().put(TOTAL_DOCS, _totalDocs + "");
    return dataTable;
  }

  private DataTable getSelectionResultDataTable() throws Exception {
    return attachMetadataToDataTable(SelectionService.transformRowSetToDataTable(_selectionResult, _dataSchema));
  }

  public DataTable getAggregationResultDataTable() throws Exception {
    DataSchema schema = AggregationService.getAggregationResultsDataSchema(_aggregationFunctionList);
    DataTableBuilder builder = new DataTableBuilder(schema);
    builder.open();
    builder.startRow();
    for (int i = 0; i < _aggregationResultList.size(); ++i) {
      switch (_aggregationFunctionList.get(i).aggregateResultDataType()) {
        case LONG:
          builder.setColumn(i, ((Long) _aggregationResultList.get(i)).longValue());
          break;
        case DOUBLE:
          builder.setColumn(i, ((Double) _aggregationResultList.get(i)).doubleValue());
          break;
        case OBJECT:
          builder.setColumn(i, _aggregationResultList.get(i));
          break;
        default:
          throw new UnsupportedOperationException("Shouldn't reach here in getAggregationResultsList()");
      }
    }
    builder.finishRow();
    builder.seal();
    return attachMetadataToDataTable(builder.build());
  }

  public void setAggregationResults(List<Serializable> aggregationResults) {
    _aggregationResultList = aggregationResults;
  }

  public HashMap<String, List<Serializable>> getAggregationGroupByResult() {
    return _aggregationGroupByResult;
  }

  public DataTable getAggregationGroupByResultDataTable() throws Exception {
    DataSchema dataSchema = GroupByAggregationService.buildDataSchema(_aggregationFunctionList);

    DataTableBuilder dataTableBuilder = new DataTableBuilder(dataSchema);
    dataTableBuilder.open();
    for (String groupedKey : _aggregationGroupByResult.keySet()) {
      dataTableBuilder.startRow();
      List<Serializable> row = _aggregationGroupByResult.get(groupedKey);
      dataTableBuilder.setColumn(0, groupedKey);
      for (int i = 0; i < row.size(); ++i) {
        switch (_aggregationFunctionList.get(i).aggregateResultDataType()) {
          case LONG:
            dataTableBuilder.setColumn(i + 1, ((Long) row.get(i)).longValue());
            break;
          case DOUBLE:
            dataTableBuilder.setColumn(i + 1, ((Double) row.get(i)).doubleValue());
            break;
          case STRING:
            dataTableBuilder.setColumn(i + 1, (String) row.get(i));
            break;
          case OBJECT:
            dataTableBuilder.setColumn(i + 1, row.get(i));
            break;
          default:
            throw new UnsupportedOperationException("Shouldn't reach here in getAggregationResultsList()");
        }
      }
      dataTableBuilder.finishRow();
    }
    dataTableBuilder.seal();
    return attachMetadataToDataTable(dataTableBuilder.build());
  }

  public List<ProcessingException> getExceptions() {
    return _processingExceptions;
  }

  public long getNumDocsScanned() {
    return _numDocsScanned;
  }

  public long getRequestId() {
    return _requestId;
  }

  public List<RowEvent> getRowEvents() {
    return _rowEvents;
  }

  public List<ResponseStatistics> getSegmentStatistics() {
    return _segmentStatistics;
  }

  public long getTimeUsedMs() {
    return _timeUsedMs;
  }

  public long getTotalDocs() {
    return _totalDocs;
  }

  public Map<String, String> getTraceInfo() {
    return _traceInfo;
  }

  public void setExceptionsList(List<ProcessingException> processingExceptions) {
    _processingExceptions = processingExceptions;
  }

  public void setNumDocsScanned(long numDocsScanned) {
    _numDocsScanned = numDocsScanned;
  }

  public void setRequestId(long requestId) {
    _requestId = requestId;
  }

  public void setRowEvents(List<RowEvent> rowEvents) {
    _rowEvents = rowEvents;
  }

  public void setSegmentStatistics(List<ResponseStatistics> segmentStatistics) {
    _segmentStatistics = segmentStatistics;
  }

  public void setTimeUsedMs(long timeUsedMs) {
    _timeUsedMs = timeUsedMs;
  }

  public void setTotalDocs(long totalDocs) {
    _totalDocs = totalDocs;
  }

  public void setTraceInfo(Map<String, String> traceInfo) {
    _traceInfo = traceInfo;
  }

  public void setAggregationFunctions(List<AggregationFunction> aggregationFunctions) {
    _aggregationFunctionList = aggregationFunctions;
  }

  public void setAggregationGroupByResult(HashMap<String, List<Serializable>> aggregationGroupByResults) {
    _aggregationGroupByResult = aggregationGroupByResults;

  }

  public void setSelectionDataSchema(DataSchema dataSchema) {
    _dataSchema = dataSchema;

  }

  public void setSelectionResult(PriorityQueue<Serializable[]> rowEventsSet) {
    _selectionResult = rowEventsSet;
  }

  public DataSchema getSelectionDataSchema() {
    return _dataSchema;
  }

  public PriorityQueue<Serializable[]> getSelectionResult() {
    return _selectionResult;
  }
}
