package com.kreview.kreview.profile;

import java.util.List;

/** Stable navigation vocabulary; recognition remains a separate rule layer. */
public final class GoodsSaleProfile {
  public static final String CONTRACT_TYPE = "GOODS_SALE";
  public static final String VERSION = "goods-sale-1";
  public record Category(String key, String label, List<String> itemTypes) {}
  public static final List<Category> CATEGORIES = List.of(
      new Category("BASIC", "合同基本信息", List.of("PARTIES", "SIGNED_ON", "EFFECTIVE_ON")),
      new Category("GOODS", "货物与质量", List.of("GOODS_NAME", "QUANTITY", "SPECIFICATION", "QUALITY")),
      new Category("PRICE_PAYMENT", "价款与支付", List.of("TOTAL", "PAYMENT_AMOUNT", "PAYMENT_DEADLINE", "PAYMENT_CONDITION")),
      new Category("DELIVERY", "交付", List.of("DELIVERY_DEADLINE", "PLACE", "TRANSPORT", "RISK_TRANSFER")),
      new Category("ACCEPTANCE", "验收与售后", List.of("STANDARD", "ACCEPTANCE_DEADLINE", "OBJECTION", "WARRANTY")),
      new Category("REMEDIES", "违约与救济", List.of("LIABILITY", "PENALTY", "INTEREST", "COMPENSATION")),
      new Category("OTHER", "其他权利义务／未分类", List.of("OTHER_CLAUSE", "UNCLASSIFIED")));
  private GoodsSaleProfile() {}
}
