package com.xin.xincodesandbox.utils;

import cn.hutool.core.date.StopWatch;
import com.xin.xincodesandbox.model.ExecuteMessage;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ProcessUtils {

    /**
     * 处理进程执行信息
     * @param process
     * @return
     */
    public static ExecuteMessage runProcessAndGetMessage(Process process, String operatorName) {
        ExecuteMessage message = new ExecuteMessage();
        try {
            // StopWatch 开启计时
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            // 等待终端执行完成
            int exitValue = process.waitFor();
            message.setExitValue(exitValue);
            if (exitValue == 0) {
                // 编译成功
                System.out.println(operatorName + "成功");
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                StringBuilder compileOutputStringBuilder = new StringBuilder();
                while ((line = bufferedReader.readLine()) != null) {
                    compileOutputStringBuilder.append(line);
                }
                message.setMessage(compileOutputStringBuilder.toString());
            } else {
                // 编译错误
                System.out.println(operatorName + "失败");
                // 输出编译信息
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                StringBuilder compileOutputStringBuilder = new StringBuilder();
                while ((line = bufferedReader.readLine()) != null) {
                    compileOutputStringBuilder.append(line);
                }
                message.setMessage(compileOutputStringBuilder.toString());
                // 输出错误信息(错误流
                bufferedReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                StringBuilder errorCompileOutputStringBuilder = new StringBuilder();
                while ((line = bufferedReader.readLine()) != null) {
                    errorCompileOutputStringBuilder.append(line);
                }
                message.setErrorMessage(errorCompileOutputStringBuilder.toString());;
            }
            stopWatch.stop();
            // 设置时间
            message.setTime(stopWatch.getLastTaskTimeMillis());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            return message;
        }
    }
}
