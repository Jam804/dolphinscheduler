/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.server.log;

import io.netty.channel.Channel;
import org.apache.commons.lang3.StringUtils;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.common.utils.LoggerUtils;
import org.apache.dolphinscheduler.plugin.task.api.utils.LogUtils;
import org.apache.dolphinscheduler.remote.command.Command;
import org.apache.dolphinscheduler.remote.command.CommandType;
import org.apache.dolphinscheduler.remote.command.log.GetAppIdRequestCommand;
import org.apache.dolphinscheduler.remote.command.log.GetAppIdResponseCommand;
import org.apache.dolphinscheduler.remote.command.log.GetLogBytesRequestCommand;
import org.apache.dolphinscheduler.remote.command.log.GetLogBytesResponseCommand;
import org.apache.dolphinscheduler.remote.command.log.RemoveTaskLogRequestCommand;
import org.apache.dolphinscheduler.remote.command.log.RemoveTaskLogResponseCommand;
import org.apache.dolphinscheduler.remote.command.log.RollViewLogRequestCommand;
import org.apache.dolphinscheduler.remote.command.log.RollViewLogResponseCommand;
import org.apache.dolphinscheduler.remote.command.log.ViewLogRequestCommand;
import org.apache.dolphinscheduler.remote.command.log.ViewLogResponseCommand;
import org.apache.dolphinscheduler.remote.processor.NettyRequestProcessor;
import org.apache.dolphinscheduler.remote.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class LoggerRequestProcessor implements NettyRequestProcessor {

    private final Logger logger = LoggerFactory.getLogger(LoggerRequestProcessor.class);

    private final ExecutorService executor;

    public LoggerRequestProcessor() {
        this.executor = Executors.newFixedThreadPool(Constants.CPUS * 2 + 1);
    }

    @Override
    public void process(Channel channel, Command command) {
        logger.info("received command : {}", command);

        // request task log command type
        final CommandType commandType = command.getType();
        switch (commandType) {
            case GET_LOG_BYTES_REQUEST:
                GetLogBytesRequestCommand getLogRequest = JSONUtils.parseObject(
                        command.getBody(), GetLogBytesRequestCommand.class);
                String path = getLogRequest.getPath();
                if (!checkPathSecurity(path)) {
                    throw new IllegalArgumentException("Illegal path");
                }
                byte[] bytes = getFileContentBytes(path);
                GetLogBytesResponseCommand getLogResponse = new GetLogBytesResponseCommand(bytes);
                channel.writeAndFlush(getLogResponse.convert2Command(command.getOpaque()));
                break;
            case VIEW_WHOLE_LOG_REQUEST:
                ViewLogRequestCommand viewLogRequest = JSONUtils.parseObject(
                        command.getBody(), ViewLogRequestCommand.class);
                String viewLogPath = viewLogRequest.getPath();
                if (!checkPathSecurity(viewLogPath)) {
                    throw new IllegalArgumentException("Illegal path");
                }
                String msg = LoggerUtils.readWholeFileContent(viewLogPath);
                ViewLogResponseCommand viewLogResponse = new ViewLogResponseCommand(msg);
                channel.writeAndFlush(viewLogResponse.convert2Command(command.getOpaque()));
                break;
            case ROLL_VIEW_LOG_REQUEST:
                RollViewLogRequestCommand rollViewLogRequest =
                        JSONUtils.parseObject(command.getBody(), RollViewLogRequestCommand.class);
                // todo: solve the NPE, this shouldn't happen in normal case
                RollViewLogResponseCommand rollViewLogRequestResponse = readPartFileContent(rollViewLogRequest);
                channel.writeAndFlush(rollViewLogRequestResponse.convert2Command(command.getOpaque()));
                break;
            case REMOVE_TAK_LOG_REQUEST:
                RemoveTaskLogRequestCommand removeTaskLogRequest = JSONUtils.parseObject(
                        command.getBody(), RemoveTaskLogRequestCommand.class);

                String taskLogPath = removeTaskLogRequest.getPath();
                if (!checkPathSecurity(taskLogPath)) {
                    throw new IllegalArgumentException("Illegal path");
                }
                File taskLogFile = new File(taskLogPath);
                boolean status = true;
                try {
                    if (taskLogFile.exists()) {
                        status = taskLogFile.delete();
                    }
                } catch (Exception e) {
                    status = false;
                }

                RemoveTaskLogResponseCommand removeTaskLogResponse = new RemoveTaskLogResponseCommand(status);
                channel.writeAndFlush(removeTaskLogResponse.convert2Command(command.getOpaque()));
                break;
            case GET_APP_ID_REQUEST:
                GetAppIdRequestCommand getAppIdRequestCommand =
                        JSONUtils.parseObject(command.getBody(), GetAppIdRequestCommand.class);
                String logPath = getAppIdRequestCommand.getLogPath();
                if (!checkPathSecurity(logPath)) {
                    throw new IllegalArgumentException("Illegal path");
                }
                List<String> appIds = LogUtils.getAppIdsFromLogFile(logPath);
                channel.writeAndFlush(new GetAppIdResponseCommand(appIds).convert2Command(command.getOpaque()));
                break;
            default:
                throw new IllegalArgumentException("unknown commandType");
        }
    }

    /**
     * LogServer only can read the logs dir.
     * @param path
     * @return
     */
    private boolean checkPathSecurity(String path) {
        String dsHome = System.getProperty("DOLPHINSCHEDULER_WORKER_HOME");
        if (StringUtils.isBlank(dsHome)) {
            dsHome = System.getProperty("user.dir");
        }
        if (StringUtils.isBlank(path)) {
            logger.warn("path is null");
            return false;
        } else {
            return path.startsWith(dsHome) && !path.contains("../") && path.endsWith(".log");
        }
    }

    public ExecutorService getExecutor() {
        return this.executor;
    }

    /**
     * get files content bytes for download file
     *
     * @param filePath file path
     * @return byte array of file
     */
    private byte[] getFileContentBytes(String filePath) {
        try (
                InputStream in = new FileInputStream(filePath);
                ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            logger.error("get file bytes error", e);
        }
        return new byte[0];
    }

    private RollViewLogResponseCommand readPartFileContent(RollViewLogRequestCommand rollViewLogRequest) {

        String rollViewLogPath = rollViewLogRequest.getPath();
        if (!checkPathSecurity(rollViewLogPath)) {
            logger.error("Log file path: {} is not a security path", rollViewLogPath);
            return RollViewLogResponseCommand.error(RollViewLogResponseCommand.Status.LOG_PATH_IS_NOT_SECURITY);
        }
        File file = new File(rollViewLogPath);
        if (!file.exists() || !file.isFile()) {
            logger.error("Log file path: {} doesn't exists", rollViewLogPath);
            return RollViewLogResponseCommand.error(RollViewLogResponseCommand.Status.LOG_FILE_NOT_FOUND);
        }

        int skipLine = rollViewLogRequest.getSkipLineNum();
        int limit = rollViewLogRequest.getLimit();
        try (Stream<String> stream = Files.lines(Paths.get(rollViewLogPath))) {
            List<String> lines = stream.skip(skipLine).limit(limit).collect(Collectors.toList());
            long totalLineNumber = stream.count();

            return RollViewLogResponseCommand.builder()
                    .currentLineNumber(skipLine + lines.size())
                    .currentTotalLineNumber(totalLineNumber)
                    .log(String.join("\r\n", lines))
                    .build();
        } catch (IOException e) {
            logger.error("Rolling view log error, meet an unknown exception, request: {}", rollViewLogRequest, e);
            return RollViewLogResponseCommand.error(RollViewLogResponseCommand.Status.UNKNOWN_ERROR);
        }
    }

}
