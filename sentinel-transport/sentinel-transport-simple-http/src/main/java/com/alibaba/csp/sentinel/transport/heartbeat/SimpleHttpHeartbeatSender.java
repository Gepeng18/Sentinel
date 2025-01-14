/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.transport.heartbeat;

import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.transport.HeartbeatSender;
import com.alibaba.csp.sentinel.transport.config.TransportConfig;
import com.alibaba.csp.sentinel.transport.heartbeat.client.SimpleHttpClient;
import com.alibaba.csp.sentinel.transport.heartbeat.client.SimpleHttpRequest;
import com.alibaba.csp.sentinel.transport.heartbeat.client.SimpleHttpResponse;
import com.alibaba.csp.sentinel.transport.endpoint.Endpoint;

import java.util.List;

/**
 * The heartbeat sender provides basic API for sending heartbeat request to provided target.
 * This implementation is based on a trivial HTTP client.
 *
 * @author Eric Zhao
 * @author Carpenter Lee
 * @author Leo Li
 */
public class SimpleHttpHeartbeatSender implements HeartbeatSender {

    private static final int OK_STATUS = 200;

    private static final long DEFAULT_INTERVAL = 1000 * 10;

    private final HeartbeatMessage heartBeat = new HeartbeatMessage();
    private final SimpleHttpClient httpClient = new SimpleHttpClient();

    private final List<Endpoint> addressList;

    private int currentAddressIdx = 0;

    public SimpleHttpHeartbeatSender() {
        // Retrieve the list of default addresses.
        List<Endpoint> newAddrs = TransportConfig.getConsoleServerList();
        if (newAddrs.isEmpty()) {
            RecordLog.warn("[SimpleHttpHeartbeatSender] Dashboard server address not configured or not available");
        } else {
            RecordLog.info("[SimpleHttpHeartbeatSender] Default console address list retrieved: {}", newAddrs);
        }
        this.addressList = newAddrs;
    }

    /**
     * Dcsp.sentinel.dashboard.server预先设置好的ip和端口号发送post请求到控制台，
     * 然后检测是否返回200，如果是则说明控制台正常，否则进行异常处理。
     */
    @Override
    public boolean sendHeartbeat() throws Exception {
        if (TransportConfig.getRuntimePort() <= 0) {
            RecordLog.info("[SimpleHttpHeartbeatSender] Command server port not initialized, won't send heartbeat");
            return false;
        }
        // 1. 获取控制台的ip和端口等信息
        Endpoint addrInfo = getAvailableAddress();
        if (addrInfo == null) {
            return false;
        }

        // 2. 设置访问参数，http调用的ip和端口，还有访问的url
        SimpleHttpRequest request = new SimpleHttpRequest(addrInfo, TransportConfig.getHeartbeatApiPath());
        //    设置版本号，端口等额外参数信息
        request.setParams(heartBeat.generateCurrentMessage());
        try {
            // 3. 发送post请求
            SimpleHttpResponse response = httpClient.post(request);
            // 如果返回值是OK_STATUS，则表示与dashBoard连接正常
            if (response.getStatusCode() == OK_STATUS) {
                return true;
            } else if (clientErrorCode(response.getStatusCode()) || serverErrorCode(response.getStatusCode())) {
                RecordLog.warn("[SimpleHttpHeartbeatSender] Failed to send heartbeat to " + addrInfo
                    + ", http status code: " + response.getStatusCode());
            }
        } catch (Exception e) {
            RecordLog.warn("[SimpleHttpHeartbeatSender] Failed to send heartbeat to " + addrInfo, e);
        }
        return false;
    }

    @Override
    public long intervalMs() {
        return DEFAULT_INTERVAL;
    }

    private Endpoint getAvailableAddress() {
        if (addressList == null || addressList.isEmpty()) {
            return null;
        }
        if (currentAddressIdx < 0) {
            currentAddressIdx = 0;
        }
        int index = currentAddressIdx % addressList.size();
        return addressList.get(index);
    }

    private boolean clientErrorCode(int code) {
        return code > 399 && code < 500;
    }

    private boolean serverErrorCode(int code) {
        return code > 499 && code < 600;
    }
}
