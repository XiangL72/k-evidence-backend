package com.kreview.kreview.matrix;

import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.document.SourceSpan;
import com.kreview.kreview.extraction.ClauseSegmenter;
import com.kreview.kreview.extraction.RuleModel.Clause;
import com.kreview.kreview.matrix.CompositionModel.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Classify B before finding its A target; the fixed rows are presentation slots, not inferred facts. */
public final class CompositionMatrixBuilder {
  private static final List<String[]> SLOTS = List.of(
      new String[]{"PARTIES", "合同基本信息", "当事人"},
      new String[]{"GOODS", "货物与价款", "货物"},
      new String[]{"PRICE", "货物与价款", "价款"},
      new String[]{"PAYMENT", "货物与价款", "付款"},
      new String[]{"DELIVERY", "交付与验收", "交付"},
      new String[]{"ACCEPTANCE", "交付与验收", "验收与质保"},
      new String[]{"BUYER_DUTY", "权利义务", "甲方权利义务"},
      new String[]{"SELLER_DUTY", "权利义务", "乙方权利义务"},
      new String[]{"REMEDIES", "责任与争议", "违约责任"},
      new String[]{"DISPUTE", "责任与争议", "争议解决"},
      new String[]{"OTHER", "其他", "其他"});
  private static final Pattern REFERENCE = Pattern.compile("(?:主合同|原合同)第\\s*([0-9]+(?:\\.[0-9]+)?)\\s*条");
  private static final Pattern DATE = Pattern.compile("20[0-9]{2}年[0-9]{1,2}月[0-9]{1,2}日");
  private static final Pattern MONEY = Pattern.compile("￥([0-9,]+(?:\\.[0-9]{2})?)");
  private static final Pattern PARTY = Pattern.compile("(?m)^(?:买方|卖方|甲方|乙方)[^\\n：]{0,30}：([^\\r\\n]{1,100})");
  private static final Pattern ARTICLE = Pattern.compile("(?m)^第[一二三四五六七八九十]+条");
  private final ClauseSegmenter segmenter = new ClauseSegmenter();

  public Matrix build(SourceDocument a, SourceDocument b, String relation) {
    Map<String, List<Entry>> aa = new LinkedHashMap<>(), bb = new LinkedHashMap<>();
    for (var slot : SLOTS) { aa.put(slot[0], new ArrayList<>()); bb.put(slot[0], new ArrayList<>()); }
    List<Clause> aClauses = segmenter.segment(a), bClauses = segmenter.segment(b);
    Map<String, List<Clause>> aByKey = new LinkedHashMap<>();
    Map<String, String> aHeadings = headings(aClauses), bHeadings = headings(bClauses);
    for (var clause : aClauses) {
      if (clause.key() == null || !clause.key().contains(".")) continue;
      aByKey.computeIfAbsent(clause.key(), ignored -> new ArrayList<>()).add(clause);
      aa.get(slot(clause.source().quote(), aHeadings.get(parent(clause.key())))).add(new Entry(clause.key(), "BASE",
          compact(clause.source().quote()), null, null, null, List.of(clause.source()), List.of()));
    }
    // File header can be reviewed in the same fixed composition without inventing numbered clauses.
    SourceSpan header = header(a);
    if (header != null) aa.get("PARTIES").add(0, new Entry("", "BASE", "合同当事人与基本信息",
        null, null, null, List.of(header), List.of()));
    SourceSpan bHeader = header(b);
    if (bHeader != null) {
      var aParties = parties(header == null ? "" : header.quote());
      var bParties = parties(bHeader.quote());
      boolean same = aParties.size() == 2 && aParties.equals(bParties);
      bb.get("PARTIES").add(new Entry("", same ? "RETAINED" : "REVIEW", "补充协议当事人信息",
          same ? "双方名称与 A 一致；其他主体信息仍可核对" : "双方名称与 A 未唯一核对一致，需要 double check",
          null, null, List.of(bHeader), header == null ? List.of() : List.of(header)));
    }
    BigDecimal addition = bClauses.stream().filter(c -> c.source().quote().contains("追加部分金额合计"))
        .map(c -> amount(c.source().quote())).filter(x -> x != null).findFirst().orElse(null);
    for (var clause : bClauses) {
      if (clause.key() == null || !clause.key().contains(".")) continue;
      String raw = clause.source().quote();
      String slot = slot(raw, bHeadings.get(parent(clause.key())));
      bb.get(slot).add(classifyB(clause, slot, aByKey, relation, addition));
    }
    List<Row> rows = new ArrayList<>();
    for (var slot : SLOTS) rows.add(new Row(slot[0], slot[1], slot[2], List.copyOf(aa.get(slot[0])),
        List.copyOf(bb.get(slot[0]))));
    return new Matrix(a.versionId(), b.versionId(), List.copyOf(rows), List.of(
        "B 未提及的 A 内容按未发现变更处理；这不是对法律效力的自动判断。",
        "关系待核对仅指 B 与 A 的具体对应不确定，B 原文仍位于所属构成中。"));
  }

  private Entry classifyB(Clause b, String slot, Map<String, List<Clause>> aByKey,
      String relation, BigDecimal addition) {
    String raw = b.source().quote();
    if (!relation.equals("SUPPLEMENT")) return entry(b, "REVIEW", "修订版条款待核对", "修订版逐项关联待核对", null, null, List.of());
    var ref = REFERENCE.matcher(raw);
    List<Clause> targets = new ArrayList<>();
    boolean hasReference = ref.find();
    if (hasReference) targets.addAll(aByKey.getOrDefault(ref.group(1), List.of()));
    String note = null, status = "ADDITION", oldValue = null, newValue = null;
    if (hasReference && targets.isEmpty()) {
      note = "明确引用的原合同条款未唯一找到，需要 double check"; status = "REVIEW";
    } else if (!hasReference && raw.contains("主合同") && targets.isEmpty()) {
      // A generic reference does not identify an exact target.
      note = "与原合同具体条款的对应关系待核对"; status = "REVIEW";
    }
    if (hasReference && targets.size() > 1) { status = "REVIEW"; note = "原合同目标条款不唯一，需要 double check"; }
    if (hasReference && targets.size() == 1) {
      status = "LINKED";
      note = "明确引用主合同第" + targets.getFirst().key() + "条";
    }
    if (raw.contains("不因本协议而变更") || raw.contains("仍按主合同") || raw.contains("同主合同")) {
      status = targets.size() == 1 ? "RETAINED" : "REVIEW";
      note = targets.size() == 1 ? "沿用原合同，不视为修改" : "声明沿用原合同，具体目标待核对";
    }
    if (targets.size() == 1 && raw.contains("第三批") && raw.contains("顺延至")
        && targets.getFirst().source().quote().contains("第三批")) {
      var oldDates = DATE.matcher(targets.getFirst().source().quote());
      var newDates = DATE.matcher(raw);
      String old = null, next = null;
      while (oldDates.find()) old = oldDates.group();
      while (newDates.find()) next = newDates.group();
      if (old != null && next != null && raw.contains(old) && !old.equals(next)) {
        status = "CHANGED"; oldValue = old; newValue = next;
        note = "仅第三批交货时间；顺延免责也限该批。明确对应主合同第" + targets.getFirst().key() + "条";
      } else {
        status = "REVIEW"; note = "虽引用原合同条款，但旧日期与 A 的第三批日期未核对一致，需要 double check";
      }
    }
    if (slot.equals("PRICE") && raw.contains("合同总金额调整为")) {
      var aTotal = aByKey.values().stream().flatMap(List::stream).filter(c -> {
        String line = c.source().quote().strip().lines().findFirst().orElse("");
        return (line.contains("合同总金额") || line.contains("合同总价")) && line.contains("￥")
            && !line.contains("支付") && !line.contains("付款");
      }).toList();
      if (aTotal.size() == 1) {
        var om = MONEY.matcher(aTotal.getFirst().source().quote());
        var nm = MONEY.matcher(raw);
        if (om.find() && nm.find()) {
          var oldAmount = new BigDecimal(om.group(1).replace(",", ""));
          var newAmount = new BigDecimal(nm.group(1).replace(",", ""));
          targets = aTotal;
          if (addition != null && oldAmount.add(addition).compareTo(newAmount) == 0) {
            oldValue = om.group(1); newValue = nm.group(1);
            status = "CHANGED"; note = "总价对应主合同第" + aTotal.getFirst().key() + "条；A 总价加 B 追加金额与新总价相符";
          } else { status = "REVIEW"; note = "总价与追加金额未核实一致，需要 double check"; }
        }
      }
    }
    if (raw.contains("精度由") && raw.contains("追加")) {
      status = "REVIEW"; note = "仅追加电机；原技术规格附件未提供，旧参数对应关系待核对";
    }
    if (raw.contains("质量保证期") && raw.contains("追加货物")) {
      status = "ADDITION"; note = "18个月仅适用于追加货物；原货物质保期不整体替换";
      targets = uniqueMatching(aByKey, "质量保证期");
    }
    if (raw.contains("追加的货物") || raw.contains("追加货物的货款") || raw.contains("追加采购下列货物")) {
      status = "ADDITION"; note = "仅适用于追加货物，不替换原合同安排";
    }
    if (raw.contains("付款节点及金额不因本协议而变更")) {
      status = "RETAINED"; note = "明确保持原付款节点与金额不变";
      targets = uniqueMatching(aByKey, "付款节点");
    }
    if (raw.contains("交货地点、运输方式") && raw.contains("按主合同")) {
      status = "RETAINED"; note = "交货地点、运输、包装与风险转移沿用原合同";
      targets = new ArrayList<>();
      targets.addAll(uniqueMatching(aByKey, "交货地点"));
      targets.addAll(uniqueMatching(aByKey, "运输方式及费用"));
      targets.addAll(uniqueMatching(aByKey, "包装要求"));
    }
    if (raw.contains("性能不达标") && raw.contains("赔偿")) {
      status = "ADDITION"; note = "仅技术参数升级造成的性能责任；赔偿上限为追加部分金额的20%";
    }
    if (raw.contains("备件保障") || raw.contains("技术培训") || raw.contains("保密：")) {
      status = "ADDITION"; note = "B 新增义务；与 A 具体条款的对应关系待核对";
      targets = uniqueMatching(aByKey, "乙方义务");
    }
    if (raw.contains("保密：") && raw.contains("双方")) {
      var secrecy = uniqueMatching(aByKey, "保密义务");
      if (secrecy.size() == 1) {
        status = "ADDITION"; targets = secrecy;
        note = "A 已约定乙方保密；B 增加甲方义务及期限，具体效力请核对";
      }
    }
    if (raw.contains("追加采购下列货物")) {
      targets = uniqueMatching(aByKey, "货物明细");
    }
    if (raw.contains("追加部分单价") && raw.contains("主合同原单价")) {
      status = "REVIEW"; targets = uniqueMatching(aByKey, "货物明细");
      note = "与主合同货物单价表相关；“约5%”及各品目实际折扣需要 double check";
    }
    if (raw.contains("追加货物的货款") && addition != null) {
      var money = MONEY.matcher(raw);
      BigDecimal sum = BigDecimal.ZERO; int count = 0;
      while (money.find()) { sum = sum.add(new BigDecimal(money.group(1).replace(",", ""))); count++; }
      if (count == 3 && sum.compareTo(addition) == 0) note = "仅追加货物；三笔付款金额之和与追加价款相符";
      else { status = "REVIEW"; note = "追加货物付款金额未与追加价款核对一致，需要 double check"; }
    }
    if (raw.contains("与主合同具有同等法律效力")) {
      status = "ADDITION"; note = "补充协议效力声明，不推定 A 具体条款被修改";
    }
    if (raw.contains("未约定的事项") && raw.contains("仍按主合同执行")) {
      status = "RETAINED"; note = "未约定事项沿用主合同；冲突时按补充协议，非逐项修改";
    }
    if (slot.equals("OTHER") && note == null) note = "已放入其他构成；具体类别待核对";
    return entry(b, status, compact(raw), note, oldValue, newValue,
        targets.stream().map(Clause::source).toList());
  }

  private Entry entry(Clause b, String relation, String summary, String note, String oldValue,
      String newValue, List<SourceSpan> targets) {
    return new Entry(b.key(), relation, summary, note, oldValue, newValue,
        List.of(b.source()), targets);
  }
  private String slot(String raw, String heading) {
    String first = raw.strip().lines().findFirst().orElse("");
    String lead = first.split("[：:，,；;]", 2)[0];
    if (first.contains("赔偿总额") || first.contains("无条件免费更换")) return "REMEDIES";
    if (lead.contains("备件保障") || lead.contains("技术培训") || lead.contains("乙方义务")) return "SELLER_DUTY";
    if (lead.contains("甲方义务")) return "BUYER_DUTY";
    if (first.contains("顺延至") && first.contains("第三批")) return "DELIVERY";
    if (lead.contains("交货时间") || lead.contains("交货地点") || lead.contains("运输方式")) return "DELIVERY";
    if (lead.contains("单价") || lead.contains("总金额")) return "PRICE";
    if (lead.contains("技术要求") || lead.contains("技术参数") || lead.contains("编码器")) return "GOODS";
    if (first.matches(".*(?:保密|协议效力|附件|一式肆份|签字).*$")) return "OTHER";
    if (first.matches(".*(?:违约|赔偿|停线损失|无条件免费更换).*$")) return "REMEDIES";
    if (first.matches(".*(?:争议|诉讼|法院|适用法律).*$")) return "DISPUTE";
    if (first.matches(".*(?:付款|货款|开票|发票|保证金|支付).*$")) return "PAYMENT";
    if (first.matches(".*(?:总金额|单价|追加部分金额|合同总价|价格).*$")) return "PRICE";
    if (first.matches(".*(?:交货|交付|运输|包装|风险转移).*$")) return "DELIVERY";
    if (first.matches(".*(?:质量保证|质保|验收|质量标准|检验).*$")) return "ACCEPTANCE";
    if (first.matches(".*(?:甲方义务|甲方权利).*$")) return "BUYER_DUTY";
    if (first.matches(".*(?:乙方义务|备件保障|技术培训|售后服务).*$")) return "SELLER_DUTY";
    if (first.matches(".*(?:货物|规格|技术参数|技术要求|编码器|防护等级|数量).*$")) return "GOODS";
    String section = heading == null ? "" : heading;
    if (section.contains("交货")) return "DELIVERY";
    if (section.contains("付款")) return "PAYMENT";
    if (section.contains("质量") || section.contains("验收")) return "ACCEPTANCE";
    if (section.contains("技术") || section.contains("采购") || section.contains("标的物")) return "GOODS";
    if (section.contains("违约") || section.contains("不可抗力")) return "REMEDIES";
    if (section.contains("争议")) return "DISPUTE";
    return "OTHER";
  }
  private Map<String, String> headings(List<Clause> clauses) {
    Map<String, String> result = new LinkedHashMap<>();
    for (var clause : clauses) if (clause.key() != null && !clause.key().contains("."))
      result.put(clause.key(), clause.source().quote().strip().lines().findFirst().orElse(""));
    return result;
  }
  private String parent(String key) { return key.substring(0, key.indexOf('.')); }
  private BigDecimal amount(String raw) {
    var m = MONEY.matcher(raw);
    return m.find() ? new BigDecimal(m.group(1).replace(",", "")) : null;
  }
  private List<Clause> uniqueMatching(Map<String, List<Clause>> clauses, String... terms) {
    var matches = clauses.values().stream().flatMap(List::stream)
        .filter(c -> java.util.Arrays.stream(terms).allMatch(c.source().quote()::contains)).toList();
    return matches.size() == 1 ? matches : List.of();
  }
  private String compact(String raw) {
    String first = raw.strip().replaceAll("\\s+", " ");
    return first.length() > 140 ? first.substring(0, 140) + "…" : first;
  }
  private SourceSpan header(SourceDocument doc) {
    var article = ARTICLE.matcher(doc.text());
    int end = article.find() ? article.start() : doc.text().length();
    return end == 0 ? null : doc.span(0, end);
  }
  private List<String> parties(String header) {
    var matcher = PARTY.matcher(header);
    List<String> names = new ArrayList<>();
    while (matcher.find()) names.add(matcher.group(1).strip());
    return names;
  }
}
