package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TFlogApplyCard;
import com.fc.v2.model.auto.TFlogStandDoc;
import com.fc.v2.service.ITFlogApplyCardService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 伐区采伐申请建档卡 Controller
 *
 * @author fuce
 * @date 2026-09-12
 */
@Api(value = "伐区采伐申请建档卡")
@Controller
@RequestMapping("/FlogApplyCardController")
public class FlogApplyCardController extends BaseController {

    private final String prefix = "admin/flogApplyCard";

    @Autowired
    private ITFlogApplyCardService flogApplyCardService;

    @ApiOperation(value = "分页跳转", notes = "分页跳转")
    @GetMapping("/view")
    @RequiresPermissions("flog:flogApplyCard:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "伐区采伐申请建档卡集合查询", action = "list")
    @ApiOperation(value = "分页查询", notes = "分页查询")
    @GetMapping("/list")
    @RequiresPermissions("flog:flogApplyCard:list")
    @ResponseBody
    public ResultTable list(TFlogApplyCard record) {
        QueryWrapper<TFlogApplyCard> queryWrapper = new QueryWrapper<TFlogApplyCard>();
        if (record.getBillNo() != null && !record.getBillNo().trim().isEmpty()) {
            queryWrapper.like("bill_no", record.getBillNo().trim());
        }
        if (record.getSubcompNo() != null && !record.getSubcompNo().trim().isEmpty()) {
            queryWrapper.like("subcomp_no", record.getSubcompNo().trim());
        }
        if (record.getSiteId() != null) {
            queryWrapper.eq("site_id", record.getSiteId());
        }
        startPage();
        com.github.pagehelper.PageInfo<TFlogApplyCard> page =
                new com.github.pagehelper.PageInfo<TFlogApplyCard>(flogApplyCardService.selectTFlogApplyCardList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @ApiOperation(value = "新增跳转", notes = "新增跳转")
    @GetMapping("/add")
    @RequiresPermissions("flog:flogApplyCard:add")
    public String add(ModelMap modelMap) {
        modelMap.addAttribute("siteList", flogApplyCardService.listSiteOptions(null));
        return prefix + "/add";
    }

    @Log(title = "伐区采伐申请建档卡新增", action = "add")
    @ApiOperation(value = "新增", notes = "新增")
    @PostMapping("/add")
    @RequiresPermissions("flog:flogApplyCard:add")
    @ResponseBody
    public AjaxResult add(TFlogApplyCard record) {
        // 校验不通过（重复/停用底档/卡号未挂底档等）由业务异常统一转 error 文案
        return toAjax(flogApplyCardService.insertTFlogApplyCard(record));
    }

    @ApiOperation(value = "修改跳转", notes = "修改跳转")
    @GetMapping("/edit/{id}")
    @RequiresPermissions("flog:flogApplyCard:edit")
    public String edit(@PathVariable("id") Long id, ModelMap modelMap) {
        TFlogApplyCard card = flogApplyCardService.selectTFlogApplyCardById(id);
        if (card == null) {
            // 已删/不存在的卡不给编辑页，避免 th:object 空指针与“改了一条影子数据”
            modelMap.addAttribute("message", "建档卡不存在或已删除");
            return "error/404";
        }
        modelMap.addAttribute("FlogApplyCard", card);
        modelMap.addAttribute("siteList", flogApplyCardService.listSiteOptions(card.getSiteId()));
        return prefix + "/edit";
    }

    @Log(title = "伐区采伐申请建档卡修改", action = "edit")
    @ApiOperation(value = "修改保存", notes = "修改保存")
    @PostMapping("/edit")
    @RequiresPermissions("flog:flogApplyCard:edit")
    @ResponseBody
    public AjaxResult editSave(TFlogApplyCard record) {
        return toAjax(flogApplyCardService.updateTFlogApplyCard(record));
    }

    @Log(title = "伐区采伐申请建档卡删除", action = "remove")
    @ApiOperation(value = "删除", notes = "删除")
    @DeleteMapping("/remove")
    @RequiresPermissions("flog:flogApplyCard:remove")
    @ResponseBody
    public AjaxResult remove(String ids) {
        return toAjax(flogApplyCardService.deleteTFlogApplyCardByIds(ids));
    }

    @ApiOperation(value = "建档卡号同底档判重", notes = "建档卡号同底档判重")
    @PostMapping("/checkBillNoUnique")
    @ResponseBody
    public int checkBillNoUnique(Long id, Integer siteId, String billNo) {
        // 与新增/编辑保存用同一个 service 口径，页面只是提前提示，不做第二套判重
        return flogApplyCardService.countBillNoDuplicate(id, siteId, billNo);
    }

    @ApiOperation(value = "可选编制单位底档", notes = "可选编制单位底档")
    @GetMapping("/siteOptions")
    @ResponseBody
    public List<TFlogStandDoc> siteOptions(Integer currentSiteId) {
        return flogApplyCardService.listSiteOptions(currentSiteId);
    }

    @Log(title = "伐区采伐申请建档卡档位重算", action = "recalc")
    @ApiOperation(value = "档位重算并落库", notes = "按底档现行阈值重算取用顺位并 UPDATE 回库")
    @PostMapping("/recalc")
    @RequiresPermissions("flog:flogApplyCard:recalc")
    @ResponseBody
    public AjaxResult recalc(Long id) {
        return toAjax(flogApplyCardService.recalcTFlogApplyCardById(id));
    }

    @Log(title = "伐区采伐申请建档卡存量搬入", action = "import")
    @ApiOperation(value = "存量数据批量搬入", notes = "与手工新增同一校验口径，整批校验通过才落库")
    @PostMapping("/importData")
    @RequiresPermissions("flog:flogApplyCard:import")
    @ResponseBody
    public AjaxResult importData(@RequestBody List<TFlogApplyCard> records) {
        int rows = flogApplyCardService.importCards(records);
        return AjaxResult.success(200, "成功搬入 " + rows + " 条建档卡", rows);
    }
}
