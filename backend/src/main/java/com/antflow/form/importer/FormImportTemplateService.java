package com.antflow.form.importer;

import com.antflow.engine.BizException;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 生成**只读回填**的 xlsx 模板：两行表头 + 隐藏的元数据 + 填写说明。
 *
 * <p>为什么坚持"系统生成"：表头第二行是字段 id，导入端据此对账，不需要任何"表头映射"配置。
 * 用户把列改名/删列可以，但**不能编造字段 id**——否则只能靠猜，历史补录就不该靠猜。
 *
 * <p>三个刻意的取舍：
 * <ol>
 *   <li>**必填的媒体字段直接拒绝**：Excel 里没有可上传的文件，模板给出一个永远填不进去的必填列，
 *       用户只会以为系统坏了。宁可在下载模板时就说清楚。</li>
 *   <li>**布局容器（span_layout）平铺**：它只是排版，没有自己的值；子字段按原顺序出列。</li>
 *   <li>**重名 label 加序号**：`备注(2)`。表头允许重复（Excel 里看着一样），但人要靠它认列，
 *       而列的身份由第二行的 fieldId 决定，改显示名不影响导入。</li>
 * </ol>
 *
 * <p>本类只留"能不能做这张模板"的判断与对外的常量：出列规则在
 * {@link FormImportColumnCollector}，POI 渲染在 {@link FormImportTemplateWriter}。
 */
@Service
public class FormImportTemplateService {

    // 模板格式常量定义在渲染器里（它是输出契约的拥有者），这里原样再暴露一次，
    // 让解析端与测试继续按 FormImportTemplateService.* 引用，不必改任何调用点。
    public static final String SHEET_DATA = FormImportTemplateWriter.SHEET_DATA;
    public static final String SHEET_META = FormImportTemplateWriter.SHEET_META;
    public static final String SHEET_DICT = FormImportTemplateWriter.SHEET_DICT;
    public static final String SHEET_HELP = FormImportTemplateWriter.SHEET_HELP;

    /** 模板版本：表结构/元数据形状变了才 +1；导入端只认 1。 */
    public static final int TEMPLATE_VERSION = FormImportTemplateWriter.TEMPLATE_VERSION;

    /** 「业务单号」是保留列：不是表单字段，写 import_key 而不是 business_no（原因见 V62）。 */
    public static final String KEY_COLUMN_ID = FormImportTemplateWriter.KEY_COLUMN_ID;
    public static final String KEY_COLUMN_LABEL = FormImportTemplateWriter.KEY_COLUMN_LABEL;

    /** 静态选项超过这个数就不做下拉校验：Excel 的下拉列表在几百项时已经没法用。 */
    public static final int MAX_DICT_OPTIONS = FormImportColumnCollector.MAX_DICT_OPTIONS;

    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper json;
    private final FormImportColumnCollector collector;
    private final FormImportTemplateWriter writer;

    @Autowired
    public FormImportTemplateService(FormDefinitionService formDefinitionService, ObjectMapper json,
                                     FormImportColumnCollector collector,
                                     FormImportTemplateWriter writer) {
        this.formDefinitionService = formDefinitionService;
        this.json = json;
        this.collector = collector;
        this.writer = writer;
    }

    /** 测试用构造器：不启动 Spring 时直接装配两个无状态协作者。 */
    FormImportTemplateService(FormDefinitionService formDefinitionService, ObjectMapper json) {
        this(formDefinitionService, json, new FormImportColumnCollector(),
            new FormImportTemplateWriter(json));
    }

    /** 生成模板字节。调用方负责鉴权与审计（本类只做渲染），这里只拦"根本做不出来"的情况。 */
    public byte[] render(long formId) {
        FormDefinition form = formDefinitionService.getById(formId);
        if (form == null || !"PUBLISHED".equals(form.getStatus())) {
            throw new BizException("IMPORT_FORM_NOT_PUBLISHED", "只有已发布的表单可以导入数据");
        }
        JsonNode schema = readSchema(form);
        List<FormImportColumnCollector.Column> columns = columns(schema);
        // 保留列（业务单号）也占一列；超过 50 列解析器会直接拒绝，生成时先说清楚。
        if (columns.size() + 1 > com.antflow.form.options.OptionTableImporter.MAX_COLUMNS) {
            throw new BizException("IMPORT_TEMPLATE_INVALID",
                "表单可填字段过多：加上「业务单号」后超过 "
                    + com.antflow.form.options.OptionTableImporter.MAX_COLUMNS
                    + " 列，无法通过 Excel 导入");
        }
        return writer.render(form, columns);
    }

    /** schema 的稳定摘要：模板与库里的定义必须一致，避免"旧模板往新表单里灌列"。 */
    public static String schemaHash(String schema) {
        return FormImportTemplateWriter.schemaHash(schema);
    }

    /** 列清单：对外沿用旧入口，规则见 {@link FormImportColumnCollector}。 */
    public List<FormImportColumnCollector.Column> columns(JsonNode schema) {
        return collector.columns(schema);
    }

    /** 供外部（导入解析器）判断某类型是否根本不进模板。 */
    public static boolean isMedia(String type) {
        return FormImportColumnCollector.isMedia(type);
    }

    private JsonNode readSchema(FormDefinition form) {
        try {
            return json.readTree(form.getSchema() == null ? "[]" : form.getSchema());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new BizException("IMPORT_TEMPLATE_INVALID", "表单结构无法解析");
        }
    }
}
