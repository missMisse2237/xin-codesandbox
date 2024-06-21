package com.xin.xincodesandbox.sandbox.impl.original;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import com.xin.xincodesandbox.model.ExecuteCodeRequest;
import com.xin.xincodesandbox.model.ExecuteCodeResponse;
import com.xin.xincodesandbox.model.ExecuteMessage;
import com.xin.xincodesandbox.model.JudgeInfo;
import com.xin.xincodesandbox.sandbox.CodeSandbox;
import com.xin.xincodesandbox.utils.ProcessUtils;
import org.springframework.stereotype.Component;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JavaNativeCodeSandboxOriginal implements CodeSandbox {

    private static final String GLOBAL_DIRECTORY_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_NAME = "Main.java";

    private static final long TIME_OUT = 5000L;

    // 可以使用布隆过滤器？
    private static final List<String> blackList = Arrays.asList("Files", "exec");

    private static final double FPP = 0.01;

    private static final WordTree wordTree;

    private static final BloomFilter<String> bloomFilter;

    private static final Funnel<String> blackFunnel;

    static {
        // 在这里初始化WordTree 和 布隆过滤器
            // 初始化WordTree
        wordTree = new WordTree();
        wordTree.addWords(blackList);
            // 初始化布隆过滤器
        blackFunnel = (from, into) -> into.putString(from, StandardCharsets.UTF_8);
        bloomFilter = BloomFilter.create(blackFunnel, blackList.size() * 2, FPP);
        blackList.forEach(bloomFilter::put);
    }


    public static void main(String[] args) {
        JavaNativeCodeSandboxOriginal javaNativeCodeSandbox = new JavaNativeCodeSandboxOriginal();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "1 3"));
        executeCodeRequest.setCode(FileUtil.readUtf8String("testCode/simpleComputeArgs/Main.java"));
        executeCodeRequest.setLanguage("java");

        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    /**
     * 执行代码
     *
     * @param executeCodeRequest
     * @return
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();

        /**
         *  校验黑名单代码（这里使用的是 Hutool工具类的 WordTree，但是能否使用布隆过滤器？
         */
        // 把这个长文本分隔开
        List<String> codeWords = Arrays.stream(code.split("[\\s.]+"))
                .filter(word -> !word.isEmpty())
                .collect(Collectors.toList());

        for (String word : codeWords) {
            if (bloomFilter.mightContain(word)) {
                System.out.println("代码可能包含敏感词：" + word);
                // 在这里你可以决定是继续检查其他单词还是直接退出循环
                // 因为布隆过滤器可能有误判，如果有问题 那就再判断一下子
                FoundWord foundWord = wordTree.matchWord(code);
                if (foundWord != null) {
                    // 代表有敏感词
                    throw new RuntimeException("存在敏感操作");
                }
            }
        }


        /**
         *  1. 把用户的代码转化成file
         */
        // 1. 把文件放到tmpCode里
        String userDir = System.getProperty("user.dir");
        String globalUserDir = userDir + File.separator + GLOBAL_DIRECTORY_NAME;
        // 判断全局代码仓库路径是否存在，如果不存在则创建
        if (!FileUtil.exist(globalUserDir)) {
            // 如果不存在，就创建一个
            FileUtil.mkdir(globalUserDir);
        }
        // 2. 使用UUID，把用户的代码隔离存放默认Java文件名都是Main.java
        String userFilePath = globalUserDir + File.separator + UUID.randomUUID();
        String userFileName = userFilePath + File.separator + GLOBAL_JAVA_NAME;
        File file = FileUtil.writeString(code, userFileName, StandardCharsets.UTF_8);

        /**
         *  2. 编译代码 得到class文件
         */
        String compileCmd = String.format("javac -encoding utf-8 %s", file.getAbsolutePath());
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            System.out.println(executeMessage);
        } catch (IOException e) {
            return getErrorResponse(e);
        }

        /**
         *  3. 执行代码
         */
        List<ExecuteMessage> outputExecuteMessageList = new ArrayList<>();
        for (String input : inputList) {
            String runCmd = String.format("java -Xmx128m -Dfile.encoding=utf-8 -cp %s Main %s", userFilePath, input);
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                new Thread(() -> {
                    try {
                        Thread.sleep(TIME_OUT);
                        runProcess.destroy(); // 注意这个runProcess是什么！这个是命令行创建出来的子进程
                    } catch (InterruptedException e) {
                        // TODO 可以抛一个信息：超时
                        throw new RuntimeException(e);
                    }
                }).start();
                ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(runProcess, "运行");
                System.out.println(executeMessage);
                outputExecuteMessageList.add(executeMessage);
            } catch (IOException e) {
                return getErrorResponse(e);
            }
        }
        /**
         *  4. 封装结果
         */
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        long maxTime = 0;
        for (ExecuteMessage executeMessage : outputExecuteMessageList) {
            // 先取一个最大时间值
            if (executeMessage.getTime() != null) {
                maxTime = Math.max(maxTime, executeMessage.getTime());
            }

            // 要判断他是否是错误的（ 看他的错误信息是否是空
            if (StrUtil.isNotBlank(executeMessage.getErrorMessage())) {
                // 代表运行错误
                executeCodeResponse.setMessage(executeMessage.getErrorMessage());
                // 3代表运行出错
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getMessage());

        }
        executeCodeResponse.setOutputList(outputList);
        // 输出的个数和message的个数相等，代表程序执行成功
        if (outputList.size() == outputExecuteMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setMessage(outputList.toString());
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        // 这里如果要获取内存，非常非常麻烦 先不实现
//        judgeInfo.setMessage();
        executeCodeResponse.setJudgeInfo(judgeInfo);

        /**
         *  5. 文件清理
         */
        if (file.getParentFile().exists()) {
            boolean del = FileUtil.del(file.getParentFile());
            System.out.println("删除" + (del ? "成功" : "失败"));
        }

        /**
         *  6. 错误处理
         */

        return executeCodeResponse;
    }

    /**
     * 抛异常的地方就用这个
     *
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(Exception e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 2代表代码沙箱错误，例如 编译就出错了  ||| 3的是运行错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}


