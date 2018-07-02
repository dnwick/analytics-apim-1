/*
 * Copyright (c) 2018 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.analytics.apim.file.impl;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.analytics.apim.file.impl.dao.FileBasedAnalyticsDAO;
import org.wso2.analytics.apim.file.impl.dto.UploadedFileInfoDTO;
import org.wso2.analytics.apim.file.impl.exception.FileBasedAnalyticsException;
import org.wso2.analytics.apim.file.impl.internal.ServiceReferenceHolder;
import org.wso2.analytics.apim.file.impl.util.FileBasedAnalyticsConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.databridge.commons.Event;
import org.wso2.carbon.event.input.adapter.core.InputEventAdapterListener;
import org.wso2.carbon.event.stream.core.EventStreamService;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * This class publishes events to streams, which are read from the uploaded usage file
 */
public class FileDataPublisher implements Runnable {

    private static final Log log = LogFactory.getLog(FileDataPublisher.class);

    private UploadedFileInfoDTO infoDTO;
    private InputEventAdapterListener inputEventAdapterListener;
    private int tenantId;
    private String tenantDomain;

    public FileDataPublisher(UploadedFileInfoDTO infoDTO, InputEventAdapterListener inputEventAdapterListener,
            String tenantDomain, int tenantId) throws FileBasedAnalyticsException {
        this.infoDTO = infoDTO;
        this.inputEventAdapterListener = inputEventAdapterListener;
        this.tenantDomain = tenantDomain;
        this.tenantId = tenantId;
    }

    @Override
    public void run() {
        log.info("Started publishing API usage in file : " + infoDTO.toString());
        publishEvents();
    }

    private void publishEvents() {

        FileInputStream fileInputStream = null;
        InputStreamReader inputStreamReader = null;
        BufferedReader bufferedReader = null;
        InputStream fileContentStream = null;
        ZipInputStream zipInputStream = null;
        try {
            //Get Content of the file and start processing
            fileContentStream = FileBasedAnalyticsDAO.getFileContent(infoDTO);
            if (fileContentStream == null) {
                log.warn("No content available in the file : " + infoDTO.toString()
                        + ". Therefore, not publishing the record.");
                FileBasedAnalyticsDAO.updateCompletion(infoDTO);
                return;
            }
            zipInputStream = new ZipInputStream(fileContentStream);
            for (ZipEntry zipEntry; (zipEntry = zipInputStream.getNextEntry()) != null; ) {
                if (zipEntry.getName().equals(FileBasedAnalyticsConstants.API_USAGE_OUTPUT_FILE_NAME)) {
                    InputStream inputStream = zipInputStream;
                    inputStreamReader = new InputStreamReader(inputStream);
                    bufferedReader  = new BufferedReader(inputStreamReader);
                    String readLine;

                    PrivilegedCarbonContext.startTenantFlow();
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain);
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(tenantId);
                    while ((readLine = bufferedReader.readLine()) != null) {
                        String[] elements = readLine.split(FileBasedAnalyticsConstants.EVENT_SEPARATOR);
                        //StreamID
                        String streamId = elements[0].split(FileBasedAnalyticsConstants.KEY_VALUE_SEPARATOR)[1];
                        //Timestamp
                        String timeStamp = elements[1].split(FileBasedAnalyticsConstants.KEY_VALUE_SEPARATOR)[1];
                        //MetaData
                        String metaData = elements[2].split(FileBasedAnalyticsConstants.KEY_VALUE_SEPARATOR)[1];
                        //correlationData
                        String correlationData = elements[3].split(FileBasedAnalyticsConstants.KEY_VALUE_SEPARATOR)[1];
                        //PayloadData
                        String payloadData = elements[4].split(FileBasedAnalyticsConstants.KEY_VALUE_SEPARATOR)[1];
                        try {

                            inputEventAdapterListener.onEvent(new Event(streamId, Long.parseLong(timeStamp),
                                    (Object[]) UsagePublisherUtils.createMetaData(metaData),
                                    (Object[]) UsagePublisherUtils.createMetaData(correlationData),
                                    UsagePublisherUtils.createPayload(streamId, payloadData)));
//                            eventStreamService.publish(new Event(streamId, Long.parseLong(timeStamp),
//                                    (Object[]) UsagePublisherUtils.createMetaData(metaData),
//                                    (Object[]) UsagePublisherUtils.createMetaData(correlationData),
//                                    UsagePublisherUtils.createPayload(streamId, payloadData)));

                        } catch (Exception e) {
                            log.warn("Error occurred while publishing event : " + Arrays.toString(elements), e);
                        }
                    }
                    PrivilegedCarbonContext.endTenantFlow();
                }
            }
            //There is no way to check the current size of the queue, hence wait 30 seconds in order to get the
            //data publisher queue cleaned up
            Thread.sleep(30000);
            //Update the database
            FileBasedAnalyticsDAO.updateCompletion(infoDTO);
            log.info("Completed publishing API Usage from file : " + infoDTO.toString());
        } catch (IOException e) {
            log.error("Error occurred while reading the API Usage file.", e);
        } catch (FileBasedAnalyticsException e) {
            log.error("Error occurred while updating the completion for the processed file.", e);
        } catch (InterruptedException e) {
            //Ignore
        } finally {
            IOUtils.closeQuietly(fileInputStream);
            IOUtils.closeQuietly(inputStreamReader);
            IOUtils.closeQuietly(bufferedReader);
            IOUtils.closeQuietly(fileContentStream);
            IOUtils.closeQuietly(zipInputStream);
        }
    }

}
