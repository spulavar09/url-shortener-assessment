package com.example.shortener.workflow;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.*;
@Component
public class WorkflowScheduler {
    private final WorkflowService service;
    private final WorkflowProcessAdapter adapter;
    private final WorkflowProperties config;
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private volatile boolean closing;
    public WorkflowScheduler(WorkflowService service,WorkflowProcessAdapter adapter,WorkflowProperties config) {
        this.service=service;
        this.adapter=adapter;
        this.config=config;
    }
    @PostConstruct public void recover() {
        service.reconcile();
    }
    @Scheduled(fixedDelay=1000) public void tick() {
        if(closing||!config.enabled())return;
        service.reconcileExpired();
        service.expireWaiting();
        for(var attempt:service.unfinishedProcesses())WorkflowProcessAdapter.terminateIdentity((Number)attempt.get("pid"),attempt.get("process_start"));
        for(int i=0;i<config.concurrency();i++) {
            var claim=service.claim();
            if(claim.isEmpty())break;
            var c=claim.get();
            workers.submit(()->service.complete(c,adapter.execute(c)));
        }
    }
    @PreDestroy public void close() {
        closing=true;
        workers.shutdown();
        // Reconcile and terminate owned children without interrupting H2-writing workers.
        service.reconcile();
        try {
            workers.awaitTermination(10,TimeUnit.SECONDS);
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
