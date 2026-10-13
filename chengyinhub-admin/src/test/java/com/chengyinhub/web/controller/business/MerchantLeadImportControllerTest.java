package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.MerchantLead;
import com.chengyinhub.business.domain.dto.MerchantLeadImportResult;
import com.chengyinhub.business.mapper.MerchantLeadMapper;
import com.chengyinhub.business.service.impl.MerchantLeadServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantLeadImportControllerTest {

    private MerchantLeadController controller;
    private MerchantLeadMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mock(MerchantLeadMapper.class);
        MerchantLeadServiceImpl service = new MerchantLeadServiceImpl();
        ReflectionTestUtils.setField(service, "merchantLeadMapper", mapper);
        controller = new MerchantLeadController();
        ReflectionTestUtils.setField(controller, "merchantLeadService", service);
        ReflectionTestUtils.setField(controller, "merchantLeadImportParser", new MerchantLeadImportParser());
    }

    @Test
    void importData_reportsExactSpreadsheetRowsAndReasonsWithoutDroppingValidRows() throws Exception {
        when(mapper.insertMerchantLead(any(MerchantLead.class))).thenReturn(1);

        XSSFWorkbook workbook = new XSSFWorkbook();
        org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("商家线索");
        Row header = sheet.createRow(0);
        String[] columns = {"商家名称", "联系电话", "地址", "经度", "纬度", "类型", "标签", "来源", "绑定会员ID", "开放联系", "开放协作", "展示状态"};
        for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);

        Row valid = sheet.createRow(1);
        valid.createCell(0).setCellValue("梧桐咖啡");
        valid.createCell(1).setCellValue("13800000000");
        valid.createCell(2).setCellValue("复兴中路 10 号");
        valid.createCell(3).setCellValue("121.466");
        valid.createCell(4).setCellValue("31.219");

        // Excel 第 3 行刻意留空，失败行号必须仍按物理行计算，不能按解析后 List 下标猜。
        Row missingName = sheet.createRow(3);
        missingName.createCell(1).setCellValue("13900000000");

        Row invalidCoordinate = sheet.createRow(4);
        invalidCoordinate.createCell(0).setCellValue("越界坐标商家");
        invalidCoordinate.createCell(3).setCellValue("181");
        invalidCoordinate.createCell(4).setCellValue("31.2");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        workbook.close();
        MockMultipartFile file = new MockMultipartFile("file", "merchant.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());

        AjaxResult response = controller.importData(file);
        MerchantLeadImportResult result = (MerchantLeadImportResult) response.get(AjaxResult.DATA_TAG);

        assertEquals(3, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
        assertEquals(2, result.getFailureCount());
        assertEquals(4, result.getFailures().get(0).getRowNumber());
        assertTrue(result.getFailures().get(0).getReason().contains("商家名称"));
        assertEquals(5, result.getFailures().get(1).getRowNumber());
        assertTrue(result.getFailures().get(1).getReason().contains("经度"));
        verify(mapper).insertMerchantLead(any(MerchantLead.class));
    }

    @Test
    void importData_identifiesTheSpecificFlagGateThatRejectedTheRow() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("商家线索");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("商家名称");
        header.createCell(1).setCellValue("开放联系");
        Row invalidFlag = sheet.createRow(1);
        invalidFlag.createCell(0).setCellValue("测试商家");
        invalidFlag.createCell(1).setCellValue("2");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        workbook.close();

        AjaxResult response = controller.importData(new MockMultipartFile("file", "merchant.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray()));
        MerchantLeadImportResult result = (MerchantLeadImportResult) response.get(AjaxResult.DATA_TAG);

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        assertEquals(2, result.getFailures().get(0).getRowNumber());
        assertTrue(result.getFailures().get(0).getReason().contains("开放联系只能是 0 或 1"));
    }

    @Test
    void importData_reportsInsertThatDidNotAffectAnyRowInsteadOfCountingItAsSuccess() throws Exception {
        when(mapper.insertMerchantLead(any(MerchantLead.class))).thenReturn(0);
        XSSFWorkbook workbook = new XSSFWorkbook();
        org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("商家线索");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("商家名称");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("未落库商家");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        workbook.close();

        AjaxResult response = controller.importData(new MockMultipartFile("file", "merchant.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray()));
        MerchantLeadImportResult result = (MerchantLeadImportResult) response.get(AjaxResult.DATA_TAG);

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        assertEquals(2, result.getFailures().get(0).getRowNumber());
        assertTrue(result.getFailures().get(0).getReason().contains("写入未生效"));
    }

    @Test
    void importData_reportsSystemFailureWithoutBlamingTheSpreadsheetRow() throws Exception {
        when(mapper.insertMerchantLead(any(MerchantLead.class))).thenThrow(new IllegalStateException("database unavailable"));
        XSSFWorkbook workbook = new XSSFWorkbook();
        org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("商家线索");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("商家名称");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("待重试商家");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        workbook.close();

        AjaxResult response = controller.importData(new MockMultipartFile("file", "merchant.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray()));
        MerchantLeadImportResult result = (MerchantLeadImportResult) response.get(AjaxResult.DATA_TAG);

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        assertEquals(2, result.getFailures().get(0).getRowNumber());
        assertTrue(result.getFailures().get(0).getReason().contains("系统写入异常"));
    }
}
