package com.xin.xincodesandbox.sandbox.impl.original;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import com.xin.xincodesandbox.model.ExecuteCodeRequest;
import com.xin.xincodesandbox.model.ExecuteCodeResponse;
import com.xin.xincodesandbox.model.ExecuteMessage;
import com.xin.xincodesandbox.model.JudgeInfo;
import com.xin.xincodesandbox.sandbox.CodeSandbox;
import com.xin.xincodesandbox.utils.ProcessUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class JavaDockerCodeSandboxOriginal implements CodeSandbox {

    private static final String GLOBAL_DIRECTORY_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_NAME = "Main.java";

    private static final long TIME_OUT = 5000L;

    // 可以使用布隆过滤器？
    private static final List<String> blackList = Arrays.asList("Files", "exec");

    private static final double FPP = 0.01;

    private static final WordTree wordTree;

    private static final BloomFilter<String> bloomFilter;

    private static final Funnel<String> blackFunnel;

    private static final String image = "openjdk:8-alpine";

    private static final DockerClient dockerClient = DockerClientBuilder.getInstance().build();

    /**
     * 静态代码块，用来初始化代码的（相当于Init，也可以写一个单独的启动类或者Config，然后放在主类里
     */
    static {
        // 在这里初始化WordTree 和 布隆过滤器
        // 初始化WordTree
        wordTree = new WordTree();
        wordTree.addWords(blackList);
        // 初始化布隆过滤器
        blackFunnel = (from, into) -> into.putString(from, StandardCharsets.UTF_8);
        bloomFilter = BloomFilter.create(blackFunnel, blackList.size() * 2, FPP);
        blackList.forEach(bloomFilter::put);
        // 初始化Docker镜像  （每一次运行都会下载一次


//        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
//        PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
//            @Override
//            public void onNext(PullResponseItem item) {
//                System.out.println("下载: " + item.getStatus());
//                super.onNext(item);
//            }
//        };
//        try {
//            pullImageCmd
//                    .exec(pullImageResultCallback)
//                    .awaitCompletion();
//        } catch (InterruptedException e) {
//            System.out.println("拉起镜像异常");
//            throw new RuntimeException(e);
//        }
//        System.out.println("下载完成 ");
    }

    /**
     * 没在单元测试里测试，直接偷懒在这里测试了！
     * @param args
     */
//    public static void main(String[] args) {
//        JavaDockerCodeSandbox javaNativeCodeSandbox = new JavaDockerCodeSandbox();
//        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
//        executeCodeRequest.setInputList(Arrays.asList("321 421", "12334 3"));
//        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
//        executeCodeRequest.setCode(code);
//        executeCodeRequest.setLanguage("java");
//        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandbox.executeCode(executeCodeRequest);
//        System.out.println("输出结果：" + executeCodeResponse.getOutputList());
//    }

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
         *  校验黑名单代码（这里使用的是 Hutool工具类的 WordTree。先使用不灵
         */
        // 把这个长文本分隔开
        List<String> codeWords = Arrays.stream(code.split("[\\s.]+"))
                .filter(word -> !word.isEmpty())
                .collect(Collectors.toList());
        /**
         * 先使用布隆过滤器，因为布隆过滤器可能会有误判率。如果布隆过滤器检查出问题了再使用Hutool的WordTree（字典树
         */
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
         *  3. 创建Docker容器
         */
        // 3.1 获取默认参数的Docker Client
        // 3.2 下载容器镜像
        // 上面两个放在static里了


        // 3.3 创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();
           // 容器挂载目录
        hostConfig.setBinds(new Bind(userFilePath, new Volume("/app")));
           // 大小为30M
        hostConfig.withMemory(100*1024*1024L);

        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true) // 禁止联网
                .withReadonlyRootfs(true)               // 禁止root目录写
                .withAttachStdin(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)
                .exec();
        String containerID = createContainerResponse.getId();
        System.out.println(createContainerResponse);

        // 3.4 启动容器
        dockerClient.startContainerCmd(containerID).exec();

        // docker exec keen_blackwell java -cp /app Main 1 3
        // 3.5 执行命令并获取结果
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            StopWatch stopWatch = new StopWatch();
            String[] inputArgsArray = inputArgs.split(" ");
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsArray);
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerID)
                    .withCmd(cmdArray)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();
            System.out.println("创建执行命令：" + execCreateCmdResponse);

            ExecuteMessage executeMessage = new ExecuteMessage();
            final String[] message = {null};
            final String[] errorMessage = {null};
            long time = 0L;
            // 判断是否超时
            final boolean[] timeout = {true};
            String execId = execCreateCmdResponse.getId();
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
                @Override
                public void onComplete() {
                    // 如果执行完成，则表示没超时
                    timeout[0] = false;
                    super.onComplete();
                }

                @Override
                public void onNext(Frame frame) {
                    StreamType streamType = frame.getStreamType();
                    if (StreamType.STDERR.equals(streamType)) {
                        errorMessage[0] = new String(frame.getPayload());
                        System.out.println("输出错误结果：" + errorMessage[0]);
                    } else {
                        message[0] = new String(frame.getPayload());
                        System.out.println("输出结果：" + message[0]);
                    }
                    super.onNext(frame);
                }
            };

            final long[] maxMemory = {0L};

            // 获取占用的内存
            StatsCmd statsCmd = dockerClient.statsCmd(containerID);
            ResultCallback<Statistics> statisticsResultCallback = statsCmd.exec(new ResultCallback<Statistics>() {

                @Override
                public void onNext(Statistics statistics) {
                    System.out.println("内存占用：" + statistics.getMemoryStats().getUsage());
                    maxMemory[0] = Math.max(statistics.getMemoryStats().getUsage(), maxMemory[0]);
                }

                @Override
                public void close() throws IOException {

                }

                @Override
                public void onStart(Closeable closeable) {

                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onComplete() {

                }
            });
            statsCmd.exec(statisticsResultCallback);
            try {
                // 开始在容器中执行编译后的java程序
                stopWatch.start();
                dockerClient.execStartCmd(execId)
                        .exec(execStartResultCallback)
                        .awaitCompletion(TIME_OUT, TimeUnit.MICROSECONDS);
                stopWatch.stop();
                time = stopWatch.getLastTaskTimeMillis();
                statsCmd.close();
            } catch (InterruptedException e) {
                System.out.println("程序执行异常");
                throw new RuntimeException(e);
            }
            executeMessage.setMessage(message[0]);
            executeMessage.setErrorMessage(errorMessage[0]);
            executeMessage.setTime(time);
            executeMessage.setMemory(maxMemory[0]);
            executeMessageList.add(executeMessage);
        }
        // 4、封装结果，跟原生实现方式完全一致
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        // 取用时最大值，便于判断是否超时
        long maxTime = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(errorMessage);
                // 用户提交的代码执行中存在错误
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
        }
        // 正常运行完成
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        // 要借助第三方库来获取内存占用，非常麻烦，此处不做实现
//        judgeInfo.setMemory();

        executeCodeResponse.setJudgeInfo(judgeInfo);

        /**
         *  文件清理
         */
        if (file.getParentFile().exists()) {
            boolean del = FileUtil.del(file.getParentFile());
            System.out.println("删除" + (del ? "成功" : "失败"));
        }
        return executeCodeResponse;
    }

    /**
     * 获取错误响应
     *
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 表示代码沙箱错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;

    }
}


