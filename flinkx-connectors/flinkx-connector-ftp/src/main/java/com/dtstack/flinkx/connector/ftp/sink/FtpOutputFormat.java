/*
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

package com.dtstack.flinkx.connector.ftp.sink;

import com.dtstack.flinkx.connector.ftp.conf.FtpConfig;

import com.dtstack.flinkx.connector.ftp.handler.FtpHandlerFactory;
import com.dtstack.flinkx.connector.ftp.handler.IFtpHandler;

import com.dtstack.flinkx.throwable.FlinkxRuntimeException;

import org.apache.flink.table.data.RowData;

import com.dtstack.flinkx.exception.WriteRecordException;
import com.dtstack.flinkx.outputformat.BaseFileOutputFormat;
import com.dtstack.flinkx.util.ExceptionUtil;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The OutputFormat Implementation which reads data from ftp servers.
 *
 * Company: www.dtstack.com
 * @author huyifan.zju@163.com
 */
public class FtpOutputFormat extends BaseFileOutputFormat {

    protected FtpConfig ftpConfig;

    /** 换行符 */
    private static final int NEWLINE = 10;

    protected List<String> columnTypes;

    protected List<String> columnNames;

    private transient IFtpHandler ftpHandler;

    private transient BufferedWriter writer;

    private transient OutputStream os;

    @Override
    protected void openSource() {
        ftpHandler = FtpHandlerFactory.createFtpHandler(ftpConfig.getProtocol());
        ftpHandler.loginFtpServer(ftpConfig);
    }

    @Override
    protected void checkOutputDir() {
        if (ftpHandler.isDirExist(tmpPath)) {
            if (ftpHandler.isFileExist(tmpPath)) {
                throw new FlinkxRuntimeException(String.format("dir:[%s] is a file", tmpPath));
            }
        } else {
            ftpHandler.mkDirRecursive(tmpPath);
        }
    }

    @Override
    protected void deleteDataDir() {
        ftpHandler.deleteAllFilesInDir(outputFilePath, null);
    }

    @Override
    protected void deleteTmpDataDir() {
        ftpHandler.deleteAllFilesInDir(tmpPath, null);
    }

    @Override
    protected void nextBlock(){
        super.nextBlock();

        if (writer != null){
            return;
        }
        String currentBlockTmpPath = tmpPath + File.separatorChar + currentFileName;
        try{
            os = ftpHandler.getOutputStream(currentBlockTmpPath);
            writer = new BufferedWriter(new OutputStreamWriter(os, ftpConfig.getEncoding()));
            LOG.info("subtask:[{}] create block file:{}", taskNumber, currentBlockTmpPath);
        } catch (IOException e){
            throw new FlinkxRuntimeException(ExceptionUtil.getErrorMessage(e));
        }

        currentFileIndex++;
    }

    @Override
    public void writeSingleRecordToFile(RowData rowData) throws WriteRecordException {
        try {
            if(writer == null){
                nextBlock();
            }

            String line = (String) rowConverter.toExternal(rowData, new String());
            this.writer.write(line);
            this.writer.write(NEWLINE);
            rowsOfCurrentBlock++;
            lastRow = rowData;
        } catch(Exception ex) {
            throw new WriteRecordException(ex.getMessage(), ex);
        }
    }

    @Override
    protected void closeSource() {
        try {
            if (writer != null){
                writer.flush();
                writer.close();
                writer = null;
                os.close();
                os = null;
            }
            //avoid Failure of FtpClient operating
            this.ftpHandler.completePendingCommand();
        }catch (Exception e) {
            throw new FlinkxRuntimeException("can't close source.", e);
        }
    }

    @Override
    public void flushDataInternal() {
        closeSource();
    }

    @Override
    protected List<String> copyTmpDataFileToDir() {
        String filePrefix = jobId + "_" + taskNumber;
        String currentFilePath = "";
        List<String> copyList = new ArrayList<>();
        try {
            List<String> dataFiles = ftpHandler.getFiles(tmpPath);
            for (String dataFile : dataFiles) {
                if (!dataFile.startsWith(filePrefix)) {
                    continue;
                }
                currentFilePath = tmpPath + File.separatorChar + dataFile;
                String newFilePath = outputFilePath + File.separatorChar + dataFile;
                ftpHandler.rename(currentFilePath, newFilePath);
                copyList.add(newFilePath);
                LOG.info("copy temp file:{} to dir:{}", currentFilePath, outputFilePath);
            }
        }catch (Exception e){
            throw new FlinkxRuntimeException(String.format("can't copy temp file:[%s] to dir:[%s]", currentFilePath, outputFilePath), e);
        }
        return copyList;
    }

    @Override
    protected void deleteDataFiles(List<String> preCommitFilePathList, String path) {
        String currentFilePath = "";
        try {
            for (String filePath : this.preCommitFilePathList) {
                if (org.apache.commons.lang3.StringUtils.equals(path, outputFilePath)) {
                    ftpHandler.deleteFile(filePath);
                    LOG.info("delete file:{}", currentFilePath);
                }
            }
        }catch (IOException e){
            throw new FlinkxRuntimeException(String.format("can't delete commit file:[%s]", currentFilePath), e);
        }
    }

    @Override
    protected void moveAllTmpDataFileToDir() {
        String dataFilePath = "";
        try {
            List<String> dataFiles = ftpHandler.getFiles(tmpPath);
            for (String dataFile : dataFiles) {
                File file = new File(dataFile);
                dataFilePath = file.getAbsolutePath();
                String newDataFilePath = outputFilePath + File.separatorChar + file.getName();
                ftpHandler.rename(dataFilePath, newDataFilePath);
                LOG.info("move temp file:{} to dir:{}", dataFilePath, outputFilePath);
            }
            ftpHandler.deleteAllFilesInDir(tmpPath, null);
        }catch (Exception e){
            throw new FlinkxRuntimeException(String.format("can't copy temp file:[%s] to dir:[%s]", dataFilePath, outputFilePath), e);
        }
    }

    @Override
    public float getDeviation() {
        return 1.0F;
    }

    @Override
    protected String getExtension() {
        return "";
    }

    @Override
    protected long getCurrentFileSize() {
        String path = tmpPath + File.separatorChar + currentFileName;
        try {
            return ftpHandler.getFileSize(path);
        }catch (IOException e){
            throw new FlinkxRuntimeException("can't get file size from ftp, file = " + path, e);
        }
    }

    @Override
    protected void writeMultipleRecordsInternal() {
        notSupportBatchWrite("FtpWriter");
    }

    public FtpConfig getFtpConfig() {
        return ftpConfig;
    }

    public void setFtpConfig(FtpConfig ftpConfig) {
        this.ftpConfig = ftpConfig;
    }

    protected void notSupportBatchWrite(String writerName) {
        throw new UnsupportedOperationException(writerName + "不支持批量写入");
    }


}
