package com.nvr.authservice.service;

import com.nvr.authservice.domain.AppUser;
import com.nvr.authservice.domain.SubscriptionPlan;
import com.nvr.authservice.domain.UserSubscription;
import com.nvr.authservice.repo.AppUserRepository;
import com.nvr.authservice.repo.SubscriptionPlanRepository;
import com.nvr.authservice.repo.UserSubscriptionCameraRepository;
import com.nvr.authservice.repo.UserSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SubscriptionServiceTest {

    private UserSubscriptionRepository userSubscriptionRepo;
    private SubscriptionPlanRepository planRepo;
    private UserSubscriptionCameraRepository userSubscriptionCameraRepo;
    private AppUserRepository appUserRepo;

    private SubscriptionService subscriptionService;

    @BeforeEach
    void setUp() {
        userSubscriptionRepo = mock(UserSubscriptionRepository.class);
        planRepo = mock(SubscriptionPlanRepository.class);
        userSubscriptionCameraRepo = mock(UserSubscriptionCameraRepository.class);
        appUserRepo = mock(AppUserRepository.class);

        subscriptionService = new SubscriptionService(
                userSubscriptionRepo,
                planRepo,
                userSubscriptionCameraRepo,
                appUserRepo
        );
    }

    @Test
    void claimsForUser_whenNoSubscriptions_returnsFreePlanWith14Days() {
        // given
        AppUser user = new AppUser();
        user.setId(1L);

        // репозиторий подписок возвращает пустой список
        when(userSubscriptionRepo.findByUserAndActiveIsTrueAndEndsAtAfter(
                eq(user),
                any(Instant.class))
        ).thenReturn(List.of());

        // в плане FREE в БД зашито 14 дней
        SubscriptionPlan freePlan = new SubscriptionPlan();
        freePlan.setId(1L);
        freePlan.setCode("FREE");
        freePlan.setArchiveDays(14);

        when(planRepo.findByCode("FREE"))
                .thenReturn(Optional.of(freePlan));

        // when
        Map<String, Object> claims = subscriptionService.claimsForUser(user);

        // then
        assertThat(claims.get("plan")).isEqualTo("FREE");
        assertThat(claims.get("archiveDays")).isEqualTo(14);
    }

    @Test
    void claimsForUser_whenCam1Subscription_returnsCam1And30Days() {
        // given
        AppUser user = new AppUser();
        user.setId(1L);

        SubscriptionPlan cam1 = new SubscriptionPlan();
        cam1.setId(2L);
        cam1.setCode("CAM_1");
        cam1.setArchiveDays(30);

        UserSubscription us = new UserSubscription();
        us.setId(10L);
        us.setUser(user);
        us.setPlan(cam1);
        us.setActive(true);
        us.setStartsAt(Instant.now().minus(1, ChronoUnit.DAYS));
        us.setEndsAt(Instant.now().plus(29, ChronoUnit.DAYS));

        when(userSubscriptionRepo.findByUserAndActiveIsTrueAndEndsAtAfter(
                eq(user),
                any(Instant.class))
        ).thenReturn(List.of(us));

        // when
        Map<String, Object> claims = subscriptionService.claimsForUser(user);

        // then
        assertThat(claims.get("plan")).isEqualTo("CAM_1");
        assertThat(claims.get("archiveDays")).isEqualTo(30);
    }

    @Test
    void claimsForUser_whenMultipleSubscriptions_picksMaxArchiveDays() {
        // given
        AppUser user = new AppUser();
        user.setId(1L);

        SubscriptionPlan free = new SubscriptionPlan();
        free.setId(1L);
        free.setCode("FREE");
        free.setArchiveDays(14);

        SubscriptionPlan cam1 = new SubscriptionPlan();
        cam1.setId(2L);
        cam1.setCode("CAM_1");
        cam1.setArchiveDays(30);

        UserSubscription subFree = new UserSubscription();
        subFree.setId(10L);
        subFree.setUser(user);
        subFree.setPlan(free);
        subFree.setActive(true);
        subFree.setStartsAt(Instant.now().minus(10, ChronoUnit.DAYS));
        subFree.setEndsAt(Instant.now().plus(10, ChronoUnit.DAYS));

        UserSubscription subCam1 = new UserSubscription();
        subCam1.setId(11L);
        subCam1.setUser(user);
        subCam1.setPlan(cam1);
        subCam1.setActive(true);

        subCam1.setStartsAt(Instant.now().minus(5, ChronoUnit.DAYS));
        subCam1.setEndsAt(Instant.now().plus(20, ChronoUnit.DAYS));

        when(userSubscriptionRepo.findByUserAndActiveIsTrueAndEndsAtAfter(
                eq(user),
                any(Instant.class))
        ).thenReturn(List.of(subFree, subCam1));

        // when
        Map<String, Object> claims = subscriptionService.claimsForUser(user);

        // then
        assertThat(claims.get("plan")).isEqualTo("CAM_1");
        assertThat(claims.get("archiveDays")).isEqualTo(30);
    }

    @Test
    void claimsForUser_byUserId_delegatesToRepoAndReturnsSameClaims() {
        // given
        AppUser user = new AppUser();
        user.setId(42L);

        when(appUserRepo.findById(42L)).thenReturn(Optional.of(user));
        when(userSubscriptionRepo.findByUserAndActiveIsTrueAndEndsAtAfter(
                eq(user),
                any(Instant.class))
        ).thenReturn(List.of());

        SubscriptionPlan freePlan = new SubscriptionPlan();
        freePlan.setCode("FREE");
        freePlan.setArchiveDays(14);
        when(planRepo.findByCode("FREE")).thenReturn(Optional.of(freePlan));

        // when
        Map<String, Object> claims = subscriptionService.claimsForUser(42L);

        // then
        assertThat(claims.get("plan")).isEqualTo("FREE");
        assertThat(claims.get("archiveDays")).isEqualTo(14);

        verify(appUserRepo).findById(42L);
    }
}
