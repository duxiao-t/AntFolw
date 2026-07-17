package com.antflow.engine.handler;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 节点处理结果 — Sprint 2 C2：
 * <ul>
 *   <li>{@link #next(JsonNode)} —— 继续推进到指定节点</li>
 *   <li>{@link #end()} —— 实例结束（终端节点）</li>
 *   <li>{@link #jump(JsonNode)} —— 跳转到非直接 child 的节点（驳回到节点等）</li>
 *   <li>{@link #halt(List)} —— 暂停（handler 已建任务，taskIds 是新建的任务 id 列表）</li>
 * </ul>
 */
public sealed interface NodeOutcome {
    JsonNode node();
    Type type();

    enum Type { NEXT, END, JUMP, HALT }

    record Next(JsonNode node) implements NodeOutcome {
        @Override public Type type() { return Type.NEXT; }
    }
    record End() implements NodeOutcome {
        @Override public JsonNode node() { return null; }
        @Override public Type type() { return Type.END; }
    }
    record Jump(JsonNode node) implements NodeOutcome {
        @Override public Type type() { return Type.JUMP; }
    }
    record Halt(List<Long> newTaskIds) implements NodeOutcome {
        @Override public JsonNode node() { return null; }
        @Override public Type type() { return Type.HALT; }
    }

    static NodeOutcome next(JsonNode n) { return new Next(n); }
    static NodeOutcome end() { return new End(); }
    static NodeOutcome jump(JsonNode n) { return new Jump(n); }
    static NodeOutcome halt(List<Long> newTaskIds) { return new Halt(newTaskIds == null ? List.of() : newTaskIds); }
}