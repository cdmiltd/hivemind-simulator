// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package ltd.cdmi.simulator.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 覆盖率记录器：按 MQTT 地址（host:port）累积平台→模拟器下行 method 集合。
 * <p>基准：{@code classpath:dji-method-catalog.json} 维护的 DJI 规范指令全集。</p>
 * <p>生命周期：与 JVM 生命周期一致，<b>断开 MQTT 时不清空</b>。
 * 覆盖率测试往往跨多次连接（切换平台/重连），需保留累积数据。</p>
 * <p>覆盖方向：仅记录平台→模拟器的下行 method（services / property_set / drc_down）。
 * 模拟器→平台的上行 method 不纳入覆盖率（开发者自知模拟器实现范围）。</p>
 * <p>线程安全：内部使用 ConcurrentHashMap + newKeySet。</p>
 */
@Component
public class CoverageRecorder {

    private static final Logger log = LoggerFactory.getLogger(CoverageRecorder.class);
    private static final String CATALOG_PATH = "dji-method-catalog.json";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 三类下行通道 */
    public static final String CHANNEL_SERVICES = "services";
    public static final String CHANNEL_DRC_DOWN = "drc_down";
    public static final String CHANNEL_PROPERTY_SET = "property_set";

    /** MQTT 地址 → 已覆盖 method 集合 */
    private final Map<String, Set<String>> coverageByHost = new ConcurrentHashMap<>();

    /** DJI 规范指令全集（按通道分类） */
    private final Map<String, Set<String>> baselineByChannel = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public CoverageRecorder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try (InputStream is = new ClassPathResource(CATALOG_PATH).getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            loadChannel(root, CHANNEL_SERVICES);
            loadChannel(root, CHANNEL_DRC_DOWN);
            loadChannel(root, CHANNEL_PROPERTY_SET);
            int total = baselineByChannel.values().stream().mapToInt(Set::size).sum();
            log.info("CoverageRecorder 已加载 DJI 指令全集: services={}, drc_down={}, property_set={}, 总计={}",
                    baselineByChannel.getOrDefault(CHANNEL_SERVICES, Set.of()).size(),
                    baselineByChannel.getOrDefault(CHANNEL_DRC_DOWN, Set.of()).size(),
                    baselineByChannel.getOrDefault(CHANNEL_PROPERTY_SET, Set.of()).size(),
                    total);
        } catch (Exception e) {
            log.error("加载 DJI 指令全集失败: {}", e.getMessage(), e);
        }
    }

    private void loadChannel(JsonNode root, String channel) {
        JsonNode node = root.path(channel);
        if (node.isMissingNode()) {
            log.warn("DJI 指令全集缺少通道: {}", channel);
            return;
        }
        Set<String> methods = new TreeSet<>();
        for (String group : List.of("common", "dock1", "dock2", "dock3")) {
            JsonNode arr = node.path(group);
            if (arr.isArray()) {
                arr.forEach(m -> {
                    String s = m.asText();
                    if (!s.isBlank() && !s.startsWith("_")) {
                        methods.add(s);
                    }
                });
            }
        }
        baselineByChannel.put(channel, Collections.unmodifiableSet(methods));
    }

    /**
     * 记录一次平台下行 method 调用。
     * @param host MQTT 地址（host:port）
     * @param method 方法名（property_set 通道统一记 "property_set"）
     */
    public void record(String host, String method) {
        if (host == null || host.isBlank() || method == null || method.isBlank()) {
            return;
        }
        coverageByHost.computeIfAbsent(host, k -> ConcurrentHashMap.newKeySet()).add(method);
    }

    /**
     * 获取所有已采集过覆盖率的 MQTT 地址列表（按首次出现顺序）。
     */
    public List<String> getHosts() {
        return new ArrayList<>(coverageByHost.keySet());
    }

    /**
     * 获取指定 MQTT 地址的覆盖率数据。
     * @return Map 含 host、按通道分类的 covered/uncovered/total 统计
     */
    public Map<String, Object> getCoverage(String host) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", host);
        result.put("generated_at", LocalDateTime.now().format(TIME_FMT));

        Set<String> covered = coverageByHost.getOrDefault(host, Set.of());
        result.put("covered_count", covered.size());

        int totalAll = 0;
        int coveredAll = 0;
        Map<String, Object> channels = new LinkedHashMap<>();
        for (String ch : List.of(CHANNEL_SERVICES, CHANNEL_DRC_DOWN, CHANNEL_PROPERTY_SET)) {
            Set<String> baseline = baselineByChannel.getOrDefault(ch, Set.of());
            Set<String> chCovered = new TreeSet<>();
            Set<String> chUncovered = new TreeSet<>();
            for (String m : baseline) {
                if (covered.contains(m)) {
                    chCovered.add(m);
                } else {
                    chUncovered.add(m);
                }
            }
            Map<String, Object> chData = new LinkedHashMap<>();
            chData.put("total", baseline.size());
            chData.put("covered_count", chCovered.size());
            chData.put("covered", chCovered);
            chData.put("uncovered", chUncovered);
            channels.put(ch, chData);
            totalAll += baseline.size();
            coveredAll += chCovered.size();
        }
        result.put("total_count", totalAll);
        result.put("channels", channels);

        // 记录不在基准中的 method（可能是模拟器多实现或平台误用）
        Set<String> unknown = new TreeSet<>(covered);
        baselineByChannel.values().forEach(unknown::removeAll);
        result.put("unknown_count", unknown.size());
        result.put("unknown", unknown);
        return result;
    }

    /**
     * 生成 HTML 覆盖率报告。
     * <p>报告结构：摘要 + 按通道分类的覆盖/未覆盖清单 + 未知 method 清单。</p>
     */
    public String generateHtmlReport(String host) {
        Map<String, Object> data = getCoverage(host);
        StringBuilder html = new StringBuilder(8192);
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        html.append("<title>DJI Cloud API 覆盖率报告 - ").append(escape(host)).append("</title>");
        html.append("<style>");
        html.append("body{font-family:'Segoe UI','Microsoft YaHei',sans-serif;background:#f0f2f5;color:#0f172a;margin:0;padding:24px;line-height:1.6;}");
        html.append("h1{margin:0 0 8px 0;font-size:22px;color:#0f172a;}");
        html.append(".meta{color:#64748b;font-size:13px;margin-bottom:20px;}");
        html.append(".summary{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:24px;}");
        html.append(".stat{background:#fff;border:1px solid #e2e8f0;border-radius:8px;padding:14px;text-align:center;}");
        html.append(".stat .num{font-size:24px;font-weight:700;color:#0ea5e9;}");
        html.append(".stat .label{font-size:12px;color:#64748b;margin-top:4px;}");
        html.append(".stat.danger .num{color:#ef4444;}");
        html.append(".stat.warn .num{color:#f59e0b;}");
        html.append(".channel{background:#fff;border:1px solid #e2e8f0;border-radius:8px;margin-bottom:16px;overflow:hidden;}");
        html.append(".channel-header{padding:12px 16px;border-bottom:1px solid #e2e8f0;background:#f8fafc;display:flex;justify-content:space-between;align-items:center;}");
        html.append(".channel-title{font-weight:600;font-size:14px;}");
        html.append(".channel-rate{font-family:monospace;font-size:13px;color:#475569;}");
        html.append(".channel-body{padding:12px 16px;}");
        html.append(".section-label{font-size:12px;font-weight:600;color:#64748b;margin:8px 0 6px 0;text-transform:uppercase;letter-spacing:0.5px;}");
        html.append(".method-list{display:flex;flex-wrap:wrap;gap:6px;}");
        html.append(".method{display:inline-block;padding:3px 10px;border-radius:4px;font-family:monospace;font-size:12px;}");
        html.append(".method.covered{background:#dcfce7;color:#166534;border:1px solid #bbf7d0;}");
        html.append(".method.uncovered{background:#fee2e2;color:#991b1b;border:1px solid #fecaca;}");
        html.append(".method.unknown{background:#fef3c7;color:#92400e;border:1px solid #fde68a;}");
        html.append(".empty{color:#94a3b8;font-size:12px;font-style:italic;}");
        html.append("</style></head><body>");

        html.append("<h1>DJI Cloud API 覆盖率报告</h1>");
        html.append("<div class=\"meta\">MQTT 地址：<strong>").append(escape(host)).append("</strong>　|　生成时间：").append(data.get("generated_at")).append("</div>");

        int total = (int) data.get("total_count");
        int covered = (int) data.get("covered_count");
        int uncovered = total - covered;
        int unknown = (int) data.get("unknown_count");
        double rate = total > 0 ? (covered * 100.0 / total) : 0;

        html.append("<div class=\"summary\">");
        html.append(statCard("基准总数", total, ""));
        html.append(statCard("已覆盖", covered, ""));
        html.append(statCard("未覆盖", uncovered, "danger"));
        html.append(statCard("覆盖率", String.format("%.1f%%", rate), "warn"));
        html.append("</div>");

        @SuppressWarnings("unchecked")
        Map<String, Object> channels = (Map<String, Object>) data.get("channels");
        String[] chLabels = {"services", "drc_down", "property_set"};
        String[] chTitles = {"Services 下行（thing/product/{sn}/services）", "DRC 下行（thing/product/{sn}/drc/down）", "Property 设置（thing/product/{sn}/property/set）"};
        for (int i = 0; i < chLabels.length; i++) {
            String ch = chLabels[i];
            @SuppressWarnings("unchecked")
            Map<String, Object> chData = (Map<String, Object>) channels.get(ch);
            int chTotal = (int) chData.get("total");
            int chCovered = (int) chData.get("covered_count");
            double chRate = chTotal > 0 ? (chCovered * 100.0 / chTotal) : 0;

            html.append("<div class=\"channel\">");
            html.append("<div class=\"channel-header\">");
            html.append("<span class=\"channel-title\">").append(chTitles[i]).append("</span>");
            html.append("<span class=\"channel-rate\">").append(chCovered).append("/").append(chTotal).append(" (").append(String.format("%.1f%%", chRate)).append(")</span>");
            html.append("</div>");
            html.append("<div class=\"channel-body\">");

            @SuppressWarnings("unchecked")
            Set<String> coveredMethods = (Set<String>) chData.get("covered");
            @SuppressWarnings("unchecked")
            Set<String> uncoveredMethods = (Set<String>) chData.get("uncovered");

            html.append("<div class=\"section-label\">未覆盖（").append(uncoveredMethods.size()).append("）</div>");
            if (uncoveredMethods.isEmpty()) {
                html.append("<div class=\"empty\">无未覆盖项</div>");
            } else {
                html.append("<div class=\"method-list\">");
                for (String m : uncoveredMethods) {
                    html.append("<span class=\"method uncovered\">").append(escape(m)).append("</span>");
                }
                html.append("</div>");
            }

            html.append("<div class=\"section-label\">已覆盖（").append(coveredMethods.size()).append("）</div>");
            if (coveredMethods.isEmpty()) {
                html.append("<div class=\"empty\">无已覆盖项</div>");
            } else {
                html.append("<div class=\"method-list\">");
                for (String m : coveredMethods) {
                    html.append("<span class=\"method covered\">").append(escape(m)).append("</span>");
                }
                html.append("</div>");
            }

            html.append("</div></div>");
        }

        // 未知 method（不在 DJI 规范内，可能是平台误用或模拟器多实现）
        @SuppressWarnings("unchecked")
        Set<String> unknownMethods = (Set<String>) data.get("unknown");
        if (!unknownMethods.isEmpty()) {
            html.append("<div class=\"channel\">");
            html.append("<div class=\"channel-header\">");
            html.append("<span class=\"channel-title\">非规范 method（不在 DJI 标准内，").append(unknownMethods.size()).append("）</span>");
            html.append("</div>");
            html.append("<div class=\"channel-body\">");
            html.append("<div class=\"method-list\">");
            for (String m : unknownMethods) {
                html.append("<span class=\"method unknown\">").append(escape(m)).append("</span>");
            }
            html.append("</div></div></div>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private String statCard(String label, Object num, String extraClass) {
        return "<div class=\"stat " + extraClass + "\"><div class=\"num\">" + num + "</div><div class=\"label\">" + label + "</div></div>";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
