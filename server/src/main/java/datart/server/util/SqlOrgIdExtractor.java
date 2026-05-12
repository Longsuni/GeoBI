/*
 * Datart
 * Copyright 2021
 * Licensed under the Apache License, Version 2.0
 */

package datart.server.util;

import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 SQL 脚本中提取「疑似组织 ID」字面量（UUID / 长数字），用于与权限侧允许的 org 列表比对。
 * 不做完整 SQL 解析，仅做保守提取；无法识别变量占位场景。
 */
public final class SqlOrgIdExtractor {

    private static final Pattern SINGLE_QUOTED = Pattern.compile("'((?:[^']|'')*)'", Pattern.DOTALL);

    /** 先捕获较长数字串，再结合 {@code minNumericOrgLen} 过滤 */
    private static final Pattern LONG_DIGIT_TOKEN = Pattern.compile("(?<![0-9])([0-9]{10,})(?![0-9])");

    private static final Pattern UUID_DASHED =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Pattern HEX32 = Pattern.compile("^[0-9a-fA-F]{32}$");

    private SqlOrgIdExtractor() {
    }

    public static Set<String> extractCandidateOrgIds(String sql, int minNumericOrgLen) {
        if (StringUtils.isBlank(sql)) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher q = SINGLE_QUOTED.matcher(sql);
        while (q.find()) {
            String literal = q.group(1).replace("''", "'");
            String n = normalizeIfOrgLike(literal, minNumericOrgLen);
            if (n != null) {
                out.add(n);
            }
        }
        Matcher d = LONG_DIGIT_TOKEN.matcher(sql);
        while (d.find()) {
            String n = normalizeNumericOrgId(d.group(1), minNumericOrgLen);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * 将外部库返回的 org 标识转为与 {@link #extractCandidateOrgIds(String, int)} 一致的可比较形式。
     *
     * @param minNumericLen 纯数字时允许的最小位数；权限侧传入 1 表示信任库中的短数字编码
     */
    public static String canonicalOrgId(String raw, int minNumericLen) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (UUID_DASHED.matcher(s).matches()) {
            return s.toLowerCase();
        }
        if (HEX32.matcher(s).matches()) {
            return s.toLowerCase();
        }
        if (s.chars().allMatch(Character::isDigit)) {
            return minNumericLen <= 0 || s.length() >= minNumericLen ? s : null;
        }
        return s;
    }

    static String normalizeIfOrgLike(String literal, int minNumericOrgLen) {
        if (literal == null) {
            return null;
        }
        String s = literal.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (UUID_DASHED.matcher(s).matches()) {
            return s.toLowerCase();
        }
        if (HEX32.matcher(s).matches()) {
            return s.toLowerCase();
        }
        return normalizeNumericOrgId(s, minNumericOrgLen);
    }

    static String normalizeNumericOrgId(String digits, int minLen) {
        if (digits == null || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        if (digits.length() < minLen) {
            return null;
        }
        return digits;
    }
}
