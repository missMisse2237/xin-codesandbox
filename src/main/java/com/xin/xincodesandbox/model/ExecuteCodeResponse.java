package com.xin.xincodesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeResponse {

    /**
     * 输入用例
     */
    private List<String> outputList;

    /**
     * 接口信息（沙箱信息，不一定是程序的运行信息
     */
    private String message;

    /**
     * 执行状态
     */
    private Integer status;

    /**
     * 程序执行信息
     */
    private JudgeInfo judgeInfo;
}
