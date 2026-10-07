package com.rentrewards.challenge.service;

import com.rentrewards.challenge.model.MemberAccount;
import com.rentrewards.challenge.model.PaymentEvent;
import com.rentrewards.challenge.model.PointsResult;
import com.rentrewards.challenge.model.ProcessingOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardsEngineRegressionTest {

    private final RewardsEngine engine = new RewardsEngine(
            new PointsCalculator(), new ProcessedEventStore());

    @Test
    void remembersCappedEventsAfterProcessingAnotherMonth() {
        MemberAccount member = new MemberAccount("member-1", 0);
        engine.processPayment(event("fill-cap", "100000", 3), member);
        PaymentEvent capped = event("capped", "1000", 3);

        assertEquals(ProcessingOutcome.CAPPED, engine.processPayment(capped, member).getOutcome());
        assertEquals(ProcessingOutcome.AWARDED,
                engine.processPayment(event("next-month", "1000", 4), member).getOutcome());
        PointsResult retry = engine.processPayment(capped, member);

        assertEquals(ProcessingOutcome.DUPLICATE, retry.getOutcome());
        assertEquals(0, retry.getPointsAwarded());
        assertEquals(100_000, member.getPointsForMonth(YearMonth.of(2026, 3)));
        assertEquals(1000, member.getPointsForMonth(YearMonth.of(2026, 4)));
    }

    @Test
    void awardsOnlyTheRemainingCapAndResetsItForTheNextMonth() {
        MemberAccount member = new MemberAccount("member-1", 0);
        engine.processPayment(event("first", "99000", 3), member);

        PointsResult partial = engine.processPayment(event("partial", "2000", 3), member);
        PointsResult nextMonth = engine.processPayment(event("april", "2000", 4), member);

        assertEquals(1000, partial.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, partial.getOutcome());
        assertEquals(2000, nextMonth.getPointsAwarded());
        assertEquals(ProcessingOutcome.AWARDED, nextMonth.getOutcome());
        assertEquals(100_000, member.getPointsForMonth(YearMonth.of(2026, 3)));
        assertEquals(2000, member.getPointsForMonth(YearMonth.of(2026, 4)));
    }

    @Test
    void separateEnginesSharingAStoreAwardTheSameEventOnlyOnce() throws Exception {
        MemberAccount member = new MemberAccount("member-1", 0);
        List<PointsResult> results = processConcurrently(
                Collections.nCopies(16, event("shared", "1500", 3)), member);

        assertEquals(1, results.stream()
                .filter(result -> result.getOutcome() == ProcessingOutcome.AWARDED).count());
        assertEquals(15, results.stream()
                .filter(result -> result.getOutcome() == ProcessingOutcome.DUPLICATE).count());
        assertEquals(1500, results.stream().mapToLong(PointsResult::getPointsAwarded).sum());
        assertEquals(1500, member.getPointsForMonth(YearMonth.of(2026, 3)));
    }

    @Test
    void concurrentDistinctEventsCannotExceedTheMonthlyCap() throws Exception {
        MemberAccount member = new MemberAccount("member-1", 0);
        List<PaymentEvent> events = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            events.add(event("distinct-" + i, "10000", 3));
        }

        List<PointsResult> results = processConcurrently(events, member);

        assertEquals(10, results.stream()
                .filter(result -> result.getOutcome() == ProcessingOutcome.AWARDED).count());
        assertEquals(6, results.stream()
                .filter(result -> result.getOutcome() == ProcessingOutcome.CAPPED).count());
        assertEquals(100_000, results.stream().mapToLong(PointsResult::getPointsAwarded).sum());
        assertEquals(100_000, member.getPointsForMonth(YearMonth.of(2026, 3)));
    }

    private PaymentEvent event(String id, String amount, int month) {
        return new PaymentEvent(id, "member-1", new BigDecimal(amount),
                false, LocalDate.of(2026, month, 1));
    }

    private List<PointsResult> processConcurrently(List<PaymentEvent> events, MemberAccount member)
            throws Exception {
        ProcessedEventStore sharedStore = new ProcessedEventStore();
        var executor = Executors.newFixedThreadPool(events.size());
        CountDownLatch ready = new CountDownLatch(events.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PointsResult>> futures = new ArrayList<>();
            for (PaymentEvent event : events) {
                RewardsEngine worker = new RewardsEngine(new PointsCalculator(), sharedStore);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return worker.processPayment(event, member);
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            List<PointsResult> results = new ArrayList<>();
            for (Future<PointsResult> future : futures) {
                results.add(future.get(2, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
