package cn.objectspace.longpolling;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;


//@RequestMapping("/listener") ，配置监听接入点，也是长轮询的入口。在获取 dataId 之后，使用 request.startAsync 将请求设置为异步，
//这样在方法结束后，不会占用 Tomcat 的线程池。
//接着 dataIdContext.put(dataId, asyncTask) 会将 dataId 和异步请求上下文给关联起来，方便配置发布时，拿到对应的上下文。
//注意这里使用了一个 guava 提供的数据结构 Multimap<String, AsyncTask> dataIdContext ，它是一个多值 Map，一个 key 可以对应多个 value，
//你也可以理解为 Map<String,List> ，但使用 Multimap 维护起来可以更方便地处理一些并发逻辑。
//至于为什么会有多值，很好理解，因为配置中心的 Server 端会接受来自多个客户端对同一个 dataId 的监听。
//timeoutChecker.schedule() 启动定时器，30s 后写入 304 响应。再结合之前客户端的逻辑，接收到 304 之后，会重新发起长轮询，形成一个循环。
//@RequestMapping("/publishConfig") ，配置发布的入口。配置变更后，根据 dataId 一次拿出所有的长轮询，为之写入变更的响应，
//同时不要忘记取消定时任务。至此，完成了一个配置变更后推送的流程。
@RestController
@Slf4j
@SpringBootApplication
public class ConfigServer {

    @Data
    private static class AsyncTask {
        // 长轮询请求的上下文，包含请求和响应体
        private AsyncContext asyncContext;
        // 超时标记
        private boolean timeout;

        public AsyncTask(AsyncContext asyncContext, boolean timeout) {
            this.asyncContext = asyncContext;
            this.timeout = timeout;
        }
    }

    // guava 提供的多值 Map，一个 key 可以对应多个 value
    private Multimap<String, AsyncTask> dataIdContext = Multimaps.synchronizedSetMultimap(HashMultimap.create());
    private ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("longPolling-timeout-checker-%d").build();
    private ScheduledExecutorService timeoutChecker = new ScheduledThreadPoolExecutor(1, threadFactory);

    // 配置监听接入点
    @RequestMapping("/listener")
    public void addListener(HttpServletRequest request, HttpServletResponse response) {

        String dataId = request.getParameter("dataId");
        
        // 开启异步
        AsyncContext asyncContext = request.startAsync(request, response);
        AsyncTask asyncTask = new AsyncTask(asyncContext, true);

        // 维护 dataId 和异步请求上下文的关联
        dataIdContext.put(dataId, asyncTask);

        // 启动定时器，30s 后写入 304 响应
        timeoutChecker.schedule(() -> {
            if (asyncTask.isTimeout()) {
                dataIdContext.remove(dataId, asyncTask);
                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                asyncContext.complete();
            }
        }, 30000, TimeUnit.MILLISECONDS);
    }

    // 配置发布接入点
    @RequestMapping("/publishConfig")
    @SneakyThrows
    public String publishConfig(String dataId, String configInfo) {
        log.info("publish configInfo dataId: [{}], configInfo: {}", dataId, configInfo);
        Collection<AsyncTask> asyncTasks = dataIdContext.removeAll(dataId);
        for (AsyncTask asyncTask : asyncTasks) {
            asyncTask.setTimeout(false);
            HttpServletResponse response = (HttpServletResponse)asyncTask.getAsyncContext().getResponse();
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().println(configInfo);
            asyncTask.getAsyncContext().complete();
        }
        return "success";
    }

    public static void main(String[] args) {
        SpringApplication.run(ConfigServer.class, args);
    }

}