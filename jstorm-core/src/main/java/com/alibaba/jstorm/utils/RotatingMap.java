/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.jstorm.utils;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * RotatingMap must be used under thread-safe environment
 *
 * Expires keys that have not been updated in the configured number of seconds.
 * The algorithm used will take between expirationSecs and expirationSecs * (1 + 1/ (numBuckets-1))
 * to actually expire the message.
 *
 * get, put, remove, containsKey, and size take O(numBuckets) time to run.
 */
public class RotatingMap<K, V> implements TimeOutMap<K, V> {
    // this default ensures things expire at most 50% past the expiration time
    private static final int DEFAULT_NUM_BUCKETS = 3;

    private Deque<Map<K, V>> buckets;

    private ExpiredCallback callback;

    private final Object lock = new Object();

    private boolean isSingleThread;

    public RotatingMap(int numBuckets, ExpiredCallback<K, V> callback, boolean isSingleThread) {
        this.isSingleThread = isSingleThread;

        if (numBuckets < 2) {
            throw new IllegalArgumentException("numBuckets must be >= 2");
        }
        if (isSingleThread) {
            buckets = new LinkedList<>();
            for (int i = 0; i < numBuckets; i++) {
                buckets.add(new HashMap<K, V>());
            }
        } else {
            buckets = new LinkedBlockingDeque<>();
            for (int i = 0; i < numBuckets; i++) {
                buckets.add(new ConcurrentHashMap<K, V>());
            }
        }
        this.callback = callback;
    }

    public RotatingMap(ExpiredCallback<K, V> callback) {
        this(DEFAULT_NUM_BUCKETS, callback, false);
    }

    public RotatingMap(int numBuckets) {
        this(numBuckets, null, false);
    }

    public RotatingMap(int numBuckets, boolean isSingleThread) {
        this(numBuckets, null, isSingleThread);
    }

    public Map<K, V> rotate() {
        Map<K, V> dead = buckets.removeLast();
        if (isSingleThread) {
            buckets.addFirst(new HashMap<K, V>());
        } else {
            buckets.addFirst(new ConcurrentHashMap<K, V>());
        }
        if (callback != null) {
            for (Entry<K, V> entry : dead.entrySet()) {
                callback.expire(entry.getKey(), entry.getValue());
            }
        }
        return dead;
    }

    @Override
    public boolean containsKey(K key) {
        for (Map<K, V> bucket : buckets) {
            if (bucket.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    public Set<K> keySet() {
        Set<K> keys = new HashSet<K>();
        for (Map<K, V> bucket : buckets) {
            keys.addAll(bucket.keySet());
        }
        return keys;
    }

    @Override
    public V get(K key) {
        for (Map<K, V> bucket : buckets) {
            V v = bucket.get(key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    @Override
    public void putHead(K key, V value) {
        buckets.peekFirst().put(key, value);
    }

    @Override
    public void put(K key, V value) {
        Iterator<Map<K, V>> it = buckets.iterator();
        Map<K, V> bucket = it.next();
        bucket.put(key, value);
        while (it.hasNext()) {
            bucket = it.next();
            bucket.remove(key);
        }
    }

    /**
     * On the side of performance, scanning from header is faster on the side of logic, it should scan from the end to first.
     */
    @Override
    public Object remove(K key) {
        for (Map<K, V> bucket : buckets) {
            Object value = bucket.remove(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Override
    public int size() {
        int size = 0;
        for (Map<K, V> bucket : buckets) {
            size += bucket.size();
        }
        return size;
    }

    public void clear() {
        for (Map<K, V> bucket : buckets) {
            bucket.clear();
        }
    }

    @Override
    public String toString() {
        return buckets.toString();
    }
}
