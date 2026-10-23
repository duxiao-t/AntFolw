package com.antflow.form.options;

import com.antflow.engine.BizException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.web.multipart.MultipartFile;

/**
 * 选项源表格导入：把上传文件或粘贴文本解析成结构化表格，并做行列规模与表头校验。
 *
 * <p>为什么独立成类：这里全部是**纯输入输出**——只读字节与文本，不碰数据库、不参与事务、不做鉴权。
 * 留在 {@link OptionSourceService} 里时，那个类有一半篇幅是 CSV 引号状态机与 POI 单元格处理，
 * 改动任意一条解析规则都要在千行文件里定位。
 *
 * <p>**无状态、不托管 Spring**：全部方法 static，没有可注入依赖，因此不存在并发共享状态。
 * 拆出来之后不要给它加 {@code @Service}——一旦它能注入 bean，就会有人在解析里顺手查库，
 * 这个类也就退化成第二个上帝类。
 *
 * <p>本类同时服务**选项源**与**表单数据导入**（{@code com.antflow.form.importer}），
 * 仍然只做纯解析：不查库、不做鉴权、不参与事务。
 */
public final class OptionTableImporter {

    /** 导入上限：文件体积、行数、列数、单元格长度。超限一律抛 BizException，由调用方转成用户可读的错误。 */
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_ROWS = 20_000;
    public static final int MAX_COLUMNS = 50;
    public static final int MAX_CELL_LENGTH = 500;

    private OptionTableImporter() { }

    /**
     * 解析入口：文件与文本**二选一**，解析完统一做一次规模校验。
     *
     * @param file      上传文件，可为 null
     * @param text      粘贴文本，可为 null
     * @param sheetName 工作表名，仅 xlsx/xls 多表时需要
     * @throws BizException 二选一不满足、超限、格式不支持、内容为空或表头不合法
     */
    public static ImportedTable read(MultipartFile file, String text, String sheetName) throws Exception {
        boolean hasFile = file != null && !file.isEmpty();
        // 两者都空或都给，都是调用方传错；这里 fail-closed，避免"文件优先"掩盖前端 bug。
        if (hasFile == (text != null && !text.isBlank())) {
            throw new BizException("OPTION_SOURCE_IMPORT_REQUIRED", "请选择一个文件或粘贴文本");
        }
        if (hasFile && file.getSize() > MAX_BYTES) throw new BizException("OPTION_SOURCE_TOO_LARGE", "导入文件不能超过 10 MB");
        byte[] bytes = hasFile ? file.getBytes() : text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new BizException("OPTION_SOURCE_TOO_LARGE", "导入内容不能超过 10 MB");
        ImportedTable table = hasFile ? parseFile(safeName(file.getOriginalFilename()), bytes, sheetName)
            : parsePastedText(stripBom(text));
        validateTable(table);
        return table;
    }

    private static ImportedTable parseFile(String name, byte[] bytes, String sheetName) throws Exception {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return parseXlsx(bytes, sheetName);
        String content = stripBom(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());
        if (lower.endsWith(".csv")) return parseDelimited(content, ',');
        if (lower.endsWith(".txt")) return parsePastedText(content);
        throw new BizException("OPTION_SOURCE_TYPE_UNSUPPORTED", "仅支持 .xls、.xlsx、.csv 和 .txt（文本需为 UTF-8）");
    }

    private static ImportedTable parsePastedText(String text) {
        // 有制表符才当作"多列"，否则一行一个选项——这条区分来自产品语义，不要为了"统一 CSV"改掉。
        if (text.contains("\t")) return parseDelimited(text, '\t');
        List<List<String>> rows = text.lines().map(String::trim).filter(value -> !value.isEmpty())
            .map(value -> List.of(value)).toList();
        return new ImportedTable(List.of("选项"), rows);
    }

    private static ImportedTable parseDelimited(String text, char delimiter) {
        List<List<String>> rows = csvRows(text, delimiter);
        if (rows.isEmpty()) throw new BizException("OPTION_SOURCE_EMPTY", "导入内容为空");
        return new ImportedTable(rows.get(0), rows.subList(1, rows.size()));
    }

    private static ImportedTable parseXlsx(byte[] bytes, String sheetName) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet;
            if (sheetName != null && !sheetName.isBlank()) {
                sheet = workbook.getSheet(sheetName.trim());
                if (sheet == null) throw new BizException("OPTION_SOURCE_SHEET_NOT_FOUND", "工作表不存在");
            } else if (workbook.getNumberOfSheets() == 1) {
                sheet = workbook.getSheetAt(0);
            } else {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < workbook.getNumberOfSheets(); i++) names.add(workbook.getSheetName(i));
                throw new BizException("OPTION_SOURCE_SHEET_REQUIRED", "请选择工作表：" + String.join("、", names));
            }
            DataFormatter formatter = new DataFormatter(java.util.Locale.CHINA);
            // 公式单元格取缓存值：取公式表达式会把 =A1+B1 原样导进选项，用户看到的是乱码。
            formatter.setUseCachedValuesForFormulaCells(true);
            List<List<String>> rows = new ArrayList<>();
            // 真实 Excel 行号（1 基）：空行被丢弃后，报错仍要指向用户看到的那一行。
            List<Integer> rowNumbers = new ArrayList<>();
            for (Row row : sheet) {
                int width = Math.max(0, row.getLastCellNum());
                if (width > MAX_COLUMNS) throw new BizException("OPTION_SOURCE_COLUMNS_INVALID", "列数不能超过 50");
                List<String> values = new ArrayList<>();
                for (int column = 0; column < width; column++) {
                    values.add(formatter.formatCellValue(row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)).trim());
                }
                if (values.stream().anyMatch(value -> !value.isBlank())) {
                    rows.add(values);
                    rowNumbers.add(row.getRowNum() + 1);
                }
                // 多读一行用于判断"是否超出上限"，真正的限额在 validateTable 里报错。
                if (rows.size() > MAX_ROWS + 1) break;
            }
            if (rows.isEmpty()) throw new BizException("OPTION_SOURCE_EMPTY", "工作表为空");
            // 第 1 行是表头：数据行的真实行号从第 2 个记录开始。
            return new ImportedTable(rows.get(0), rows.subList(1, rows.size()),
                rowNumbers.subList(1, rowNumbers.size()));
        }
    }

    /** CSV 解析用状态机而非 split：引号内的分隔符与换行不能被切断，`""` 是转义的引号。 */
    private static List<List<String>> csvRows(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    cell.append('"'); index++;
                } else quoted = !quoted;
            } else if (current == delimiter && !quoted) {
                row.add(cell.toString().trim()); cell.setLength(0);
            } else if ((current == '\n' || current == '\r') && !quoted) {
                if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                row.add(cell.toString().trim()); cell.setLength(0);
                if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(current);
        }
        // 引号未闭合说明文件被截断或编码错乱：报错比"尽力解析"好，否则用户会把半张表当全量导入。
        if (quoted) throw new BizException("OPTION_SOURCE_CSV_INVALID", "CSV 引号未闭合");
        row.add(cell.toString().trim());
        if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(row);
        return rows;
    }

    private static void validateTable(ImportedTable table) {
        if (table.columns().isEmpty() || table.columns().size() > MAX_COLUMNS) {
            throw new BizException("OPTION_SOURCE_COLUMNS_INVALID", "列数必须为 1 到 50");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : table.columns()) {
            String column = raw == null ? "" : raw.trim();
            if (column.isBlank() || column.length() > 100 || !unique.add(column)) {
                throw new BizException("OPTION_SOURCE_HEADER_INVALID", "表头必须非空、唯一且不超过 100 字符");
            }
        }
        if (table.rows().isEmpty() || table.rows().size() > MAX_ROWS) {
            throw new BizException("OPTION_SOURCE_ROWS_INVALID", "数据行数必须为 1 到 20000");
        }
        for (int index = 0; index < table.rows().size(); index++) {
            List<String> row = table.rows().get(index);
            if (row.size() > table.columns().size()) {
                // +2：第 1 行是表头，用户视角从 1 开始数行号。
                throw new BizException("OPTION_SOURCE_ROW_INVALID", "第 " + (index + 2) + " 行数据列数超过表头列数");
            }
            for (int column = 0; column < row.size(); column++) {
                if (row.get(column) != null && row.get(column).length() > MAX_CELL_LENGTH) {
                    throw new BizException("OPTION_SOURCE_CELL_TOO_LONG", "第 " + (index + 2) + " 行第 " + (column + 1) + " 列超过 500 字符");
                }
            }
        }
    }

    /** 一行值按表头压成 Map；缺列补空串，避免下游 JSON 出现 key 缺失与 null 两种形态。 */
    public static Map<String, String> rowMap(List<String> columns, List<String> values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            row.put(columns.get(index).trim(), index < values.size() ? values.get(index) : "");
        }
        return row;
    }

    /** 只取文件名本身：浏览器可能传 `C:\fakepath\x.csv` 或带目录的路径，直接入库会泄露客户端路径。 */
    public static String safeName(String value) {
        if (value == null || value.isBlank()) return "import";
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static String stripBom(String value) { return value.startsWith("\uFEFF") ? value.substring(1) : value; }

    /**
     * 解析结果：第一行是表头，其余是数据行。
     *
     * <p>{@code sourceRows} 与 {@code rows} **一一对应**，是每行在原始 Excel 里的行号（1 基）。
     * 为什么必须带：解析会把整行为空的行丢掉，于是 {@code rows} 的下标不再等于用户看到的行号；
     * 表单数据导入按行报错时若用下标 +2，报出的行会随空行数量漂移，用户根本找不到问题行。
     *
     * <p>2 参构造器保留给 CSV/粘贴文本等"行号就是顺序"的来源，也是既有调用点的兼容入口。
     */
    public record ImportedTable(List<String> columns, List<List<String>> rows,
                                List<Integer> sourceRows) {
        public ImportedTable {
            columns = List.copyOf(columns);
            rows = rows == null ? List.of() : List.copyOf(rows);
            sourceRows = sourceRows == null || sourceRows.size() != rows.size()
                ? sequentialRows(rows.size()) : List.copyOf(sourceRows);
        }

        /** 兼容构造器：没有真实行号来源时，按"第 2 行起"顺序编号。 */
        public ImportedTable(List<String> columns, List<List<String>> rows) {
            this(columns, rows, null);
        }

        private static List<Integer> sequentialRows(int count) {
            List<Integer> numbers = new ArrayList<>(count);
            for (int index = 0; index < count; index++) numbers.add(index + 2);
            return numbers;
        }
    }
}
