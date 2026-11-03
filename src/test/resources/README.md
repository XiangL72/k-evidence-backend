# Synthetic development fixtures

These examples test limited Chinese goods-sale rules. They contain synthetic text and predefined expected results; they are not real customer contracts or an independent accuracy benchmark.

`v1/F01`–`F12` cover amount identity, payment and delivery deadlines, explicit references, missing or ambiguous targets, complex edits, liability text, document ordering and Unicode evidence. `F13`–`F15` contain scenario metadata; concrete input, persistence and budget behavior is exercised by the Java test suites rather than counted as extra rule fixtures.

`fixtures/v2/A01`–`A10` cover visible original facts, explicit total-price changes, increments versus final values, inconsistent old values, duplicate targets, distinct installments/batches, ambiguous dates, goods labels, unclassified clauses and conflicting changes.

Expected values are independent of the production extractor. Chinese literals are functional test data and remain in their original language. Source intervals use UTF-16 half-open offsets.
