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
package com.alibaba.csp.sentinel.property;

import com.alibaba.csp.sentinel.log.RecordLog;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class DynamicSentinelProperty<T> implements SentinelProperty<T> {

    protected Set<PropertyListener<T>> listeners = new CopyOnWriteArraySet<>();
    private T value = null;

    public DynamicSentinelProperty() {
    }

    public DynamicSentinelProperty(T value) {
        super();
        this.value = value;
    }

    @Override
    public void addListener(PropertyListener<T> listener) {
        listeners.add(listener);
        listener.configLoad(value);
    }

    @Override
    public void removeListener(PropertyListener<T> listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean updateValue(T newValue) {
        // 判断传入的规则是否和之前相等
        if (isEqual(value, newValue)) {
            return false;
        }
        RecordLog.info("[DynamicSentinelProperty] Config will be updated to: {}", newValue);

        // 替换新规则
        value = newValue;
        // 针对之前add进来的所有监听者，通知所有的监听器更新一下规则配置
        for (PropertyListener<T> listener : listeners) {
            listener.configUpdate(newValue);
        }
        return true;
    }

    private boolean isEqual(T oldValue, T newValue) {
        if (oldValue == null && newValue == null) {
            return true;
        }

        if (oldValue == null) {
            return false;
        }

        return oldValue.equals(newValue);
    }

    public void close() {
        listeners.clear();
    }
}
