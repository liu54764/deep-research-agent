package com.dra.infra.progress;

import com.dra.contracts.dto.TaskEvent;
import com.dra.domain.model.ProgressEvent;
import com.dra.domain.port.ProgressStream;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream per task: replayable SSE events. The stream is capped so a runaway activity
 * cannot grow memory unbounded.
 */
@Component
public class RedisProgressStream implements ProgressStream {

    private static final int MAX_EVENTS = 2000;
    private static final Duration TTL = Duration.ofDays(7);
    private static final String EVENT_FIELD = "e";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisProgressStream(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void emit(TaskEvent event) {
        Map<String, String> fields = Map.of(EVENT_FIELD, write(event));
        String key = eventsKey(event.taskId());
        redis.opsForStream().add(StreamRecords.newRecord().in(key).ofMap(fields));
        redis.opsForStream().trim(key, MAX_EVENTS, true);
        redis.expire(key, TTL);
    }

    @Override
    public void updateState(String taskId, Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        String key = stateKey(taskId);
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, TTL);
    }

    @Override
    public Map<String, String> snapshot(String taskId) {
        Map<Object, Object> raw = redis.opsForHash().entries(stateKey(taskId));
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
        return result;
    }

    @Override
    public List<ProgressEvent> read(String taskId, String lastEventId, Duration block, int count) {
        String from = (lastEventId == null || lastEventId.isBlank()) ? "0-0" : lastEventId;
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                StreamReadOptions.empty().count(count).block(block),
                StreamOffset.create(eventsKey(taskId), ReadOffset.from(from)));
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<ProgressEvent> events = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            Object payload = record.getValue().get(EVENT_FIELD);
            if (payload == null) {
                continue;
            }
            events.add(new ProgressEvent(record.getId().getValue(), read(String.valueOf(payload))));
        }
        return events;
    }

    private String eventsKey(String taskId) {
        return "drs:task:" + taskId + ":events";
    }

    private String stateKey(String taskId) {
        return "drs:task:" + taskId + ":state";
    }

    private String write(TaskEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize task event", e);
        }
    }

    private TaskEvent read(String json) {
        try {
            return objectMapper.readValue(json, TaskEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot deserialize task event", e);
        }
    }
}
