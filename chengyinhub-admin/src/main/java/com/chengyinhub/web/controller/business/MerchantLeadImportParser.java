package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.MerchantLeadImportRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 解析商家线索 Excel，并保留物理行号供逐行失败报告使用。 */
@Component
public class MerchantLeadImportParser {

    public List<MerchantLeadImportRow> parse(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要导入的 Excel 文件");
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getRow(0) == null) throw new IllegalArgumentException("Excel 缺少表头");
            Map<String, Integer> columns = headerColumns(sheet.getRow(0));
            if (!columns.containsKey("商家名称")) throw new IllegalArgumentException("Excel 缺少必填列：商家名称");
            List<MerchantLeadImportRow> rows = new ArrayList<>();
            DataFormatter formatter = new DataFormatter();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row source = sheet.getRow(index);
                if (isEmpty(source)) continue;
                MerchantLeadImportRow row = new MerchantLeadImportRow();
                row.setRowNumber(index + 1);
                row.setName(read(source, columns, "商家名称", formatter, row));
                row.setPhone(read(source, columns, "联系电话", formatter, row));
                row.setAddress(read(source, columns, "地址", formatter, row));
                row.setLongitude(read(source, columns, "经度", formatter, row));
                row.setLatitude(read(source, columns, "纬度", formatter, row));
                row.setCategory(read(source, columns, "类型", formatter, row));
                row.setTags(read(source, columns, "标签", formatter, row));
                row.setSource(read(source, columns, "来源", formatter, row));
                row.setMemberId(read(source, columns, "绑定会员ID", formatter, row));
                row.setContactOpen(read(source, columns, "开放联系", formatter, row));
                row.setCoopOpen(read(source, columns, "开放协作", formatter, row));
                row.setStatus(read(source, columns, "展示状态", formatter, row));
                rows.add(row);
            }
            return rows;
        }
    }

    private Map<String, Integer> headerColumns(Row header) {
        Map<String, Integer> columns = new HashMap<>();
        DataFormatter formatter = new DataFormatter();
        for (Cell cell : header) {
            String label = formatter.formatCellValue(cell).trim();
            if (!label.isEmpty()) columns.put(label, cell.getColumnIndex());
        }
        return columns;
    }

    private boolean isEmpty(Row row) {
        if (row == null) return true;
        DataFormatter formatter = new DataFormatter();
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).trim().isEmpty()) return false;
        }
        return true;
    }

    private String read(Row source, Map<String, Integer> columns, String label,
                        DataFormatter formatter, MerchantLeadImportRow row) {
        Integer column = columns.get(label);
        if (column == null) return null;
        Cell cell = source.getCell(column);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.FORMULA && row.getParseError() == null) {
            row.setParseError(label + "不允许使用公式");
        }
        return formatter.formatCellValue(cell).trim();
    }
}
