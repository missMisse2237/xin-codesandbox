package com.xin.xincodesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 进程执行信息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ExecuteMessage {

    /**
     * 终端运行返回码，0正常 2错误
     */
    private Integer exitValue;

    /**
     * 终端运行信息
     */
    private String message;

    /**
     * 如果终端报错，则有错误信息
     */
    private String errorMessage;

    /**
     * 最大内存限制
     */
    private Long Memory;

    private Long time;
}
