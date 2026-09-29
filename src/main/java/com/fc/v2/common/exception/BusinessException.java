package com.fc.v2.common.exception;

/**
 * 业务校验异常：用于编号重复、底档停用等「同一桩事只能有一个口径」的拒绝场景，
 * 由全局异常解析器统一转成 AjaxResult.error(msg) 返回界面。
 *
 * @author fuce
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BusinessException(String message) {
        super(message);
    }
}
