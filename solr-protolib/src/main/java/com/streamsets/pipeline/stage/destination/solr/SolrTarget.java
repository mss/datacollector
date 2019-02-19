/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.solr;


import com.esotericsoftware.minlog.Log;
import com.google.common.base.Joiner;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseTarget;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.lib.util.JsonUtil;
import com.streamsets.pipeline.solr.api.Errors;
import com.streamsets.pipeline.solr.api.SdcSolrTarget;
import com.streamsets.pipeline.solr.api.SdcSolrTargetFactory;
import com.streamsets.pipeline.solr.api.TargetFactorySettings;
import com.streamsets.pipeline.stage.common.DefaultErrorRecordHandler;
import com.streamsets.pipeline.stage.common.ErrorRecordHandler;
import com.streamsets.pipeline.stage.processor.scripting.ProcessingMode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SolrTarget extends BaseTarget {
  private final static Logger LOG = LoggerFactory.getLogger(SolrTarget.class);

  private final InstanceTypeOptions instanceType;
  private final String solrURI;
  private final String zookeeperConnect;
  private final String defaultCollection;
  private final ProcessingMode indexingMode;
  private final boolean fieldsAlreadyMappedInRecord;
  private final String recordSolrFieldsPath;
  private final List<SolrFieldMappingConfig> fieldNamesMap;
  private final boolean kerberosAuth;
  private final MissingFieldAction missingFieldAction;
  private final boolean skipValidation;
  private final boolean waitFlush;
  private final boolean waitSearcher;
  private final boolean softCommit;
  private final boolean ignoreOptionalFields;

  private ErrorRecordHandler errorRecordHandler;
  private SdcSolrTarget sdcSolrTarget;
  private List<String> requiredFieldNamesMap;
  private List<String> optionalFieldNamesMap;

  public SolrTarget(
      final InstanceTypeOptions instanceType,
      final String solrURI,
      final String zookeeperConnect,
      final ProcessingMode indexingMode,
      final boolean fieldsAlreadyMappedInRecord,
      final String recordSolrFieldsPath,
      final List<SolrFieldMappingConfig> fieldNamesMap,
      String defaultCollection,
      boolean kerberosAuth,
      MissingFieldAction missingFieldAction,
      boolean skipValidation,
      boolean waitFlush,
      boolean waitSearcher,
      boolean softCommit,
      boolean ignoreOptionalFields
  ) {
    this.instanceType = instanceType;
    this.solrURI = solrURI;
    this.zookeeperConnect = zookeeperConnect;
    this.defaultCollection = defaultCollection;
    this.indexingMode = indexingMode;
    this.fieldsAlreadyMappedInRecord = fieldsAlreadyMappedInRecord;
    this.recordSolrFieldsPath = recordSolrFieldsPath;
    this.fieldNamesMap = fieldNamesMap;
    this.kerberosAuth = kerberosAuth;
    this.missingFieldAction = missingFieldAction;
    this.skipValidation = skipValidation;
    this.waitFlush = waitFlush;
    this.waitSearcher = waitSearcher;
    this.softCommit = softCommit;
    this.ignoreOptionalFields = ignoreOptionalFields;
  }

  @Override
  protected List<ConfigIssue> init() {
    List<ConfigIssue> issues = super.init();
    errorRecordHandler = new DefaultErrorRecordHandler(getContext());

    boolean solrInstanceInfo = true;

    if (StringUtils.isBlank(recordSolrFieldsPath)) {
      issues.add(getContext().createConfigIssue(Groups.SOLR.name(), "recordSolrFieldsPath", Errors.SOLR_11));
    }

    if(SolrInstanceType.SINGLE_NODE.equals(instanceType.getInstanceType()) && (solrURI == null || solrURI.isEmpty())) {
      solrInstanceInfo = false;
      issues.add(getContext().createConfigIssue(Groups.SOLR.name(), "solrURI", Errors.SOLR_00));
    } else if(SolrInstanceType.SOLR_CLOUD.equals(instanceType.getInstanceType()) &&
      (zookeeperConnect == null || zookeeperConnect.isEmpty())) {
      solrInstanceInfo = false;
      issues.add(getContext().createConfigIssue(Groups.SOLR.name(), "zookeeperConnect", Errors.SOLR_01));
    }

    if (fieldNamesMap == null || fieldNamesMap.isEmpty() && !fieldsAlreadyMappedInRecord) {
      issues.add(getContext().createConfigIssue(Groups.SOLR.name(), "fieldNamesMap", Errors.SOLR_02));
    }

    if (solrInstanceInfo) {
      TargetFactorySettings settings = new TargetFactorySettings(
          instanceType.toString(),
          solrURI,
          zookeeperConnect,
          defaultCollection,
          kerberosAuth,
          skipValidation,
          waitFlush,
          waitSearcher,
          softCommit,
          ignoreOptionalFields,
          fieldsAlreadyMappedInRecord
      );
      sdcSolrTarget = SdcSolrTargetFactory.create(settings).create();
      try {
        sdcSolrTarget.init();
        this.requiredFieldNamesMap = sdcSolrTarget.getRequiredFieldNamesMap();
        if (fieldsAlreadyMappedInRecord && !ignoreOptionalFields) {
          this.optionalFieldNamesMap = sdcSolrTarget.getOptionalFieldNamesMap();
        }
      } catch (Exception ex) {
        String configName = "solrURI";
        if(InstanceTypeOptions.SOLR_CLOUD.equals(instanceType.getInstanceType())) {
          configName = "zookeeperConnect";
        }
        issues.add(getContext().createConfigIssue(Groups.SOLR.name(), configName, Errors.SOLR_03, ex.toString(), ex));
      }
    }

    return issues;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void write(Batch batch) throws StageException {
    Iterator<Record> it = batch.getRecords();
    List<Map<String, Object>> batchFieldMap = new ArrayList();
    List<Record> recordsBackup = new ArrayList<>();
    boolean atLeastOne = false;

    while (it.hasNext()) {
      atLeastOne = true;
      Record record = it.next();
      Map<String, Object> fieldMap = new HashMap();
      boolean correctRecord = true;
      if (fieldsAlreadyMappedInRecord) {
        try {
          Field recordSolrFields = record.get(recordSolrFieldsPath);
          if (recordSolrFields == null) {
            correctRecord = false;
            handleError(record, Errors.SOLR_10, recordSolrFieldsPath);
          }

          Field.Type recordSolrFieldType = recordSolrFields.getType();
          Map<String, Field> recordFieldMap = null;

          if (recordSolrFieldType == Field.Type.MAP) {
            recordFieldMap = recordSolrFields.getValueAsMap();
          } else if (recordSolrFieldType == Field.Type.LIST_MAP) {
            recordFieldMap = recordSolrFields.getValueAsListMap();
          } else {
            correctRecord = false;
            handleError(record, Errors.SOLR_09, recordSolrFieldsPath, recordSolrFields.getType().name());
          }

          if (correctRecord) {
            // for each record field check if it's in the solr required fields list and save it for later
            if (requiredFieldNamesMap != null && !requiredFieldNamesMap.isEmpty()) {
              correctRecord = checkRecordContainsSolrFields(
                  recordFieldMap,
                  record,
                  requiredFieldNamesMap,
                  Errors.SOLR_07
              );
            }

            // check record contain optional fields if needed
            if (!ignoreOptionalFields) {
              if (optionalFieldNamesMap != null && !optionalFieldNamesMap.isEmpty()) {
                correctRecord = checkRecordContainsSolrFields(
                    recordFieldMap,
                    record,
                    optionalFieldNamesMap,
                    Errors.SOLR_08
                );
              }
            }

            if (correctRecord) {
              // add fields to fieldMap to later add document to Solr
              for (Map.Entry<String, Field> recordFieldMapEntry : recordFieldMap.entrySet()) {
                fieldMap.put(
                    recordFieldMapEntry.getKey(),
                    JsonUtil.fieldToJsonObject(record, recordFieldMapEntry.getValue())
                );
              }
            }
          }

          if (!correctRecord) {
            record = null;
          }
        } catch (OnRecordErrorException ex) {
          sendOnRecordErrorExceptionToHandler(record, Errors.SOLR_07, ex);
          record = null;
        }

      } else {
        try {
          Set<String> missingFields = new HashSet<>();
          for (SolrFieldMappingConfig fieldMapping : fieldNamesMap) {
            Field field = record.get(fieldMapping.field);
            if (field == null) {
              if (ignoreOptionalFields) {
                if (requiredFieldNamesMap != null && requiredFieldNamesMap.contains(fieldMapping.field)) {
                  missingFields.add(fieldMapping.field);
                }
              }
            } else {
              fieldMap.put(fieldMapping.solrFieldName, JsonUtil.fieldToJsonObject(record, field));
            }
          }

          if (!missingFields.isEmpty()) {
            String errorParams = Joiner.on(",").join(missingFields);
            handleError(record, Errors.SOLR_06, errorParams);
            record = null;
          }
        } catch (OnRecordErrorException ex) {
          sendOnRecordErrorExceptionToHandler(record, Errors.SOLR_06, ex);
          record = null;
        }
      }

      if (record != null) {
        try {
          if (ProcessingMode.BATCH.equals(indexingMode)) {
            batchFieldMap.add(fieldMap);
            recordsBackup.add(record);
          } else {
            sdcSolrTarget.add(fieldMap);
          }
        } catch (StageException ex) {
          sendOnRecordErrorExceptionToHandler(record, Errors.SOLR_04, ex);
        }
      }
    }

    if (atLeastOne) {
      try {
        if (ProcessingMode.BATCH.equals(indexingMode)) {
          sdcSolrTarget.add(batchFieldMap);
        }
        sdcSolrTarget.commit();
      } catch (StageException ex) {
        try {
          errorRecordHandler.onError(recordsBackup, ex);
        } catch (StageException ex2) {
          errorRecordHandler.onError(recordsBackup, ex2);
        }
      }
    }
  }

  @Override
  public void destroy() {
    if(this.sdcSolrTarget != null){
      try {
        this.sdcSolrTarget.destroy();
      } catch (Exception e) {
        Log.error(e.toString());
      }
    }
    super.destroy();
  }

  /**
   * Checks whether the record contains solr fields in solrFieldsMap or not.
   *
   * @param recordFieldMap Fields contained in the reocrd
   * @param record The whole record
   * @param solrFieldsMap Solr fields that will be searched in record fields
   * @param errorToThrow error to use if missing Solr fields are detected
   * @return true if record contains all solr fields in solrFieldsMap otherwise false
   * @throws StageException Exception thrown by handleError method call
   */
  private boolean checkRecordContainsSolrFields(
      Map<String, Field> recordFieldMap,
      Record record,
      List<String> solrFieldsMap,
      Errors errorToThrow
  ) throws StageException {
    // for (Map.Entry<String, Field> recordFieldMapEntry : recordFieldMap.entrySet())
    List<String> fieldsFound = new ArrayList<>();

    recordFieldMap.keySet().forEach(recordFieldKey -> {
      if (solrFieldsMap.contains(recordFieldKey)) {
        fieldsFound.add(recordFieldKey);
      }
    });

    // if record does not contain solr fields then process error accordingly
    if (solrFieldsMap.size() != fieldsFound.size()) {
      Set<String> missingFields = new HashSet<>();
      solrFieldsMap.forEach(requiredField -> {
        if (!fieldsFound.contains(requiredField)) {
          missingFields.add(requiredField);
        }
      });

      handleError(record, errorToThrow, Joiner.on(",").join(missingFields));

      return false;
    }

    return true;
  }

  /**
   * Handles an error that occurred when processing a record. The error can either be logged or thrown in an exception.
   *
   * @param record The record which was being processed
   * @param errorTemplate The error template to be thrown if required
   * @param errorMessage The error specific message
   * @throws StageException Exception thrown if missingFieldAction indicates to stop the pipeline or send the record
   * to error. If missingFieldAction value is unknown an exception is also thrown
   */
  private void handleError(Record record, Errors errorTemplate, String errorMessage) throws StageException {
    handleError(record, errorTemplate, new String[] {errorMessage});
  }

  /**
   * Handles an error that occurred when processing a record. The error can either be logged or thrown in an exception.
   *
   * @param record The record which was being processed
   * @param errorTemplate The error template to be thrown if required
   * @param errorArguments The error specific arguments
   * @throws StageException Exception thrown if missingFieldAction indicates to stop the pipeline or send the record
   * to error. If missingFieldAction value is unknown an exception is also thrown
   */
  private void handleError(Record record, Errors errorTemplate, String ... errorArguments) throws StageException {
    switch (missingFieldAction) {
      case DISCARD:
        LOG.debug(errorTemplate.getMessage(), errorArguments);
        break;
      case STOP_PIPELINE:
        throw new StageException(errorTemplate, errorArguments);
      case TO_ERROR:
        throw new OnRecordErrorException(record, errorTemplate, errorArguments);
      default: //unknown operation
        LOG.debug("Sending record to error due to unknown operation {}", missingFieldAction);
        throw new OnRecordErrorException(record, errorTemplate, errorArguments);
    }
  }

  /**
   * Send exception ex to errorRecordHandler in order to let the handler process it.
   *
   * @param record The record which was being processed when the exception was thrown
   * @param error The error template
   * @param ex The exception that was thrown
   * @throws StageException Exception thrown by the errorRecordHandler when handling the exception ex
   */
  private void sendOnRecordErrorExceptionToHandler(Record record, Errors error, StageException ex)
      throws StageException {
    errorRecordHandler.onError(new OnRecordErrorException(
        record,
        error,
        record.getHeader().getSourceId(),
        ex.toString(),
        ex
    ));
  }
}
