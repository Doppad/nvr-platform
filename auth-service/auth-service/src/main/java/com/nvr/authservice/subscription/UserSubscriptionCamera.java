package com.nvr.authservice.subscription;


import com.nvr.authservice.domain.UserSubscription;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "user_subscription_camera",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_sub_camera",
                columnNames = {"user_subscription_id", "camera_id"}
        )
)

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSubscriptionCamera {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Конкретная подписка пользователя (CAM_1 или CAM_3)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_subscription_id", nullable = false)
    private UserSubscription userSubscription;

    // ID камеры (из nvr-сервиса / общей схемы камер)
    @Column(name = "camera_id", nullable = false)
    private Long cameraId;
}
