package com.paymentreminder.notification.repository;

import java.time.Instant;

import com.paymentreminder.notification.entity.TelegramPollingState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TelegramPollingStateRepository extends JpaRepository<TelegramPollingState, String> {

    /**
     * Records the polling position, never moving it backwards. The upsert keeps concurrent pollers
     * from losing updates and avoids a SELECT-then-INSERT race on the first run.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into telegram_polling_state (consumer_name, next_update_id, updated_at)
            values (:consumerName, :offset, :now)
            on conflict (consumer_name) do update
                set next_update_id = greatest(telegram_polling_state.next_update_id, excluded.next_update_id),
                    updated_at = excluded.updated_at
            """, nativeQuery = true)
    int saveOffset(@Param("consumerName") String consumerName, @Param("offset") long offset,
            @Param("now") Instant now);
}
