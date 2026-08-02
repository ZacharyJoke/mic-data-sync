package com.mic.datasync.shared.id;

import java.util.Objects;
import java.util.UUID;

/**
 * 领域标识类型集合。
 *
 * <p>四种 ID 都序列化为 UUID 字符串，但 Java 类型不同，禁止在方法签名中
 * 用裸 {@link String} 混用，避免把任务 ID 误传给批次 ID 等类型错误。</p>
 */
public final class Identifiers {

    private Identifiers() {
    }

    /** 实例标识：一个 dataDir 生命周期内保持不变。 */
    public record InstanceId(UUID value) implements DomainId {
        public InstanceId {
            Objects.requireNonNull(value, "instanceId 不能为空");
        }

        public static InstanceId generate() {
            return new InstanceId(UUID.randomUUID());
        }

        public static InstanceId fromString(String s) {
            return new InstanceId(UUID.fromString(s));
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    /** 任务标识。 */
    public record TaskId(UUID value) implements DomainId {
        public TaskId {
            Objects.requireNonNull(value, "taskId 不能为空");
        }

        public static TaskId generate() {
            return new TaskId(UUID.randomUUID());
        }

        public static TaskId fromString(String s) {
            return new TaskId(UUID.fromString(s));
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    /** 运行标识：一次 Run 在其生命周期内保持不变。 */
    public record RunId(UUID value) implements DomainId {
        public RunId {
            Objects.requireNonNull(value, "runId 不能为空");
        }

        public static RunId generate() {
            return new RunId(UUID.randomUUID());
        }

        public static RunId fromString(String s) {
            return new RunId(UUID.fromString(s));
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    /** 批次标识：重试必须复用同一 batchId。 */
    public record BatchId(UUID value) implements DomainId {
        public BatchId {
            Objects.requireNonNull(value, "batchId 不能为空");
        }

        public static BatchId generate() {
            return new BatchId(UUID.randomUUID());
        }

        public static BatchId fromString(String s) {
            return new BatchId(UUID.fromString(s));
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    /** 领域 ID 通用契约：均可转换为字符串表示。 */
    public interface DomainId {
        @Override
        String toString();
    }
}
