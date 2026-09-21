package com.paymentreminder.notification.repository;

import com.paymentreminder.notification.entity.TelegramConversation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramConversationRepository extends JpaRepository<TelegramConversation, Long> {
}
