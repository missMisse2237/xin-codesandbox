# xin-code-sandbox

## 项目介绍
代码沙箱部分，这一部分也可以单独的作为一个项目，这一部分仅仅是运行代码，不涉及任何的判题逻辑，用来解耦，通过开放api与判题服务进行连接。

因为使用了Docker，通过VM 安装了Ubuntu20，并且通过Jetbrains的网管服务进行SSH编程，有一说一，真的很方便！！唯一的缺点是代码提示能力弱了点。【我使用的是VM，很多坑重装了好多次Ubuntu，不推荐WSL，感觉坑会更多 】
## 具体细节

- 使用模板模式定义了JavaCodeSandboxTemplate，然后又有两个继承类（使用Docker 和 不使用Docker）

- 在Linux上操作Docker

- 因为是内部调用，所以没有做特别负责的鉴权，只是对请求染色。[现在是直接写死的，也可以在yml中定义key-value，定期更换，或者使用SpringTask定时任务轮询换秘钥]



## 亮点
- JAVA安全管理器【直接在windows 编译，运行】
  - 使用Java安全管理器和自定义的SecurityManager对用户提交的代码进行权限控制，e.g. 关闭读写文件的功能。
- 在Windows上（最原始的方式）实现代码沙箱
  - 编译  javac -encoding utf-8 xxx.java
  - 运行 java -Xmx256m -Dfile.encoding=UTF-8 -cp xxx Main xx
```java
Process compileProcess = Runtime.getRuntime().exec(compileCmd);
ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
```
  

- Docker
    - 在Docker上拉取JDK8，通过容器更方便的保证沙箱的安全性

## 关于安全：
- 超时限制
  - windows：通过守护进程，在守护进程中定义一个Sleep，如果睡醒了就直接Destroy
  - docker：
```java
// 在回调函数中要定义一个标示[默认 超时，然后时间要是不 change flge]
dockerClient.execStartCmd(execId)
        .exec(execStartResultCallback)
        .awaitCompletion(TIME_OUT, TimeUnit.MICROSECONDS);
```

- 内存限制：
  - windows是运行时采用命令-Xmx256m，限制堆栈总内存。
  - Docker: 自定义一个HostConfig，简单太多了！
```java
CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
HostConfig hostConfig = new HostConfig();
hostConfig.withMemory(100 * 1000 * 1000L);// 内存
hostConfig.withMemorySwap(0L);
hostConfig.withCpuCount(1L);// CPU
CreateContainerResponse createContainerResponse = containerCmd
        .withHostConfig(hostConfig)
        .exec();
```


- 代码限制:
  - 黑白名单：先试用布隆过滤器，如果布隆过滤器检测到有问题（fpp设置为0.01），在使用Hutool的 WordTree进行进一步的过滤，提高用户使用体验，避免误操作。【因为是在编译前的操作，所以原生和Docker都采用了这个步骤】

- 权限限制：
  - windows：自定义Security Manager，但是非常不好用。
  - Docker:
```java
// windows
// 需要继承SecurityManager，然后想限制什么就重写什么方法
@Override
public void checkWrite(String file) {
    throw new SecurityException("权限异常");
}
```

```java
// Docker
CreateContainerResponse createContainerResponse = containerCmd
        .withHostConfig(hostConfig)
        .withNetworkDisabled(true)
        .withReadonlyRootfs(true);
```

- 运行环境隔离：后来采用Docker
```java
// Docker
// 限制网络资源
CreateContainerResponse createContainerResponse = containerCmd
        .withHostConfig(hostConfig)
        .withNetworkDisabled(true)
```

### 一些BUG的总结
- 坑1，在Linux上安装jetbrians gatway时有可能一直卡在某个位置，首先建议开个全局VPN，第二就是安装和windows版本一样的remote gatway。

- 坑2，第一次在Linux上配置服务，很头疼。在windows本地可以run，在linux上run就报错。原因：Maven仓库没配置好
```shell
sudo apt install maven
```
- 1. 仅仅安装这个maven不行，要改成ali的镜像
```shell
sudo /usr/local/maven/conf/settings.xml
```
- 2. 手动设置本地仓库位置！

- 坑3，在linux上配置Docker容器，记得要使用高级一点的封装DockerClint，不要用DockerHttpCLient，这个和JDBC一样狗都不用



  