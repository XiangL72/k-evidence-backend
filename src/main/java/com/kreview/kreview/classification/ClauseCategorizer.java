package com.kreview.kreview.classification;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounded presentation classifier. Multiple category signals remain OTHER, never silently picked. */
public final class ClauseCategorizer {
  public record Classification(String categoryKey, List<String> candidateCategories, String basis) {}
  public String category(String text) { return classify(text).categoryKey(); }
  public Classification classify(String text) {
    String heading = text.split("[\r\n。；;]", 2)[0];
    String scope = heading.length() <= 50 ? heading : heading.substring(0, 50);
    Set<String> matches = new LinkedHashSet<>();
    if (has(scope, "买方", "卖方", "甲方", "乙方", "签订", "签署", "生效", "当事人")) matches.add("BASIC");
    if (has(scope, "货物", "商品", "数量", "规格", "质量")) matches.add("GOODS");
    if (has(scope, "总价", "金额", "价款", "付款", "支付", "尾款")) matches.add("PRICE_PAYMENT");
    if (has(scope, "交付", "交货", "运输", "风险转移")) matches.add("DELIVERY");
    if (has(scope, "验收", "异议", "保修", "售后", "维修")) matches.add("ACCEPTANCE");
    if (has(scope, "违约", "责任", "利息", "赔偿")) matches.add("REMEDIES");
    return new Classification(matches.size() == 1 ? matches.iterator().next() : "OTHER",
        List.copyOf(matches), matches.isEmpty() ? "NO_KEYWORD" : "HEADING_KEYWORD");
  }
  private boolean has(String text, String... words) {
    for (String word : words) if (text.contains(word)) return true;
    return false;
  }
}
