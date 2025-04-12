package com.example.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.point.domain.event.trigger.PointHistoryEvent;
import com.example.point.domain.model.Point;
import com.example.point.domain.model.PointHistory;
import com.example.point.domain.model.PointHistoryActionType;
import com.example.point.domain.model.PointRewardType;
import com.example.point.domain.model.PointStatus;
import com.example.point.presentation.request.PointCreateRequest;
import com.example.point.presentation.request.PointUseCancelRequest;
import com.example.point.presentation.request.PointUseRequest;
import com.example.point.service.port.PointHistoryRepository;
import com.example.point.service.port.PointRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PointServiceTest {

    @Mock
    PointRepository pointRepository;

    @Mock
    PointHistoryRepository pointHistoryRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    PointService pointService;

    @Captor
    ArgumentCaptor<PointHistoryEvent> captor;

    @DisplayName("포인트를 적립 할 수 있다.")
    @Test
    void canCreateByManual() {

        // given
        LocalDateTime expiredDate = LocalDateTime.now().plusDays(30);

        Point newPoint =
            createPoint(1L, PointRewardType.MANUAL, 1000, expiredDate);

        PointCreateRequest request = new PointCreateRequest(1L, 1000, expiredDate, PointRewardType.MANUAL);

        given(pointRepository.save(any(Point.class))).willReturn(newPoint);
        ReflectionTestUtils.setField(newPoint, "pointKey", 1L);

        // when
        Point savePoint = pointService.savePoint(request);

        // then
        assertThat(savePoint.getPointKey()).isEqualTo(1L);
        assertThat(savePoint.getUserKey()).isEqualTo(1L);
        assertThat(savePoint.getRewardType()).isEqualTo(PointRewardType.MANUAL);
        assertThat(savePoint.getStatus()).isEqualTo(PointStatus.ACTIVE);
        assertThat(savePoint.getTotalAmount()).isEqualTo(1000);
        assertThat(savePoint.getExpiredDate()).isEqualTo(expiredDate);

        verify(eventPublisher).publishEvent(captor.capture());
    }

    @DisplayName("적립된 포인트를 취소할 수 있다.")
    @Test
    void canCancel() {

        // given
        Point point =
            createPoint(1L, PointRewardType.MANUAL, 1000, LocalDateTime.now().plusDays(30));

        PointHistory pointHistory = PointHistory.create(point, PointHistoryActionType.SAVE, point.getTotalAmount(), null, null);

        Point cPoint = Point.builder()
            .pointKey(point.getPointKey())
            .userKey(point.getUserKey())
            .status(PointStatus.CANCELED)
            .rewardType(point.getRewardType())
            .totalAmount(point.getTotalAmount())
            .remainAmount(0)
            .expiredDate(point.getExpiredDate())
            .build();

        given(pointHistoryRepository.findSaveByPointKey(anyLong())).willReturn(Optional.ofNullable(pointHistory));
        given(pointRepository.save(any())).willReturn(cPoint);

        // when
        Point cancelPoint = pointService.cancelSavePoint(1L);

        // then
        assertThat(cancelPoint.getStatus()).isEqualTo(PointStatus.CANCELED);
        assertThat(cancelPoint.getRemainAmount()).isZero();
        assertThat(cancelPoint.isCanceled()).isTrue();

        verify(eventPublisher).publishEvent(captor.capture());
    }

    @DisplayName("포인트를 사용할 수 있다.")
    @Test
    void canUsePoint() {

        // given
        Point point =
            createPoint(1L, PointRewardType.MANUAL, 1000, LocalDateTime.now().plusDays(30));

        List<Point> points = new ArrayList<>();
        points.add(point);

        PointUseRequest useRequest = new PointUseRequest(1L, 1234L, 600);

        given(pointRepository.findUsablePointsByUserKey(anyLong()))
            .willReturn(points);

        // when
        List<Point> usePoints =  pointService.usePoint(useRequest);

        // then
        assertThat(usePoints).hasSize(1);
        assertThat(usePoints)
            .extracting(Point::getUserKey).containsExactly(1L);
        assertThat(usePoints)
            .extracting(Point::getRemainAmount).containsExactly(400);

        verify(eventPublisher).publishEvent(captor.capture());
    }

    @DisplayName("여러개의 포인트가 있을 경우 수기 지급 포인트를 우선으로 사용하고, 만료일이 짧은 순서로 사용된다.")
    @Test
    void canUseOfPriority() {

        // given
        Point firstUsePoint =
            createPoint(1L, PointRewardType.MANUAL, 500, LocalDateTime.now().plusDays(5));
        Point secondUsePoint =
            createPoint(1L, PointRewardType.MANUAL, 500, LocalDateTime.now().plusDays(30));
        Point thirdUsePoint =
            createPoint(1L, PointRewardType.OTHER, 500, LocalDateTime.now().plusDays(3));
        Point fourthUsePoint =
            createPoint(1L, PointRewardType.OTHER, 500, LocalDateTime.now().plusDays(60));

        List<Point> points = new ArrayList<>();
        points.add(firstUsePoint);
        points.add(secondUsePoint);
        points.add(thirdUsePoint);
        points.add(fourthUsePoint);

        PointUseRequest useRequest = new PointUseRequest(1L, 1234L, 1300);

        given(pointRepository.findUsablePointsByUserKey(anyLong())).willReturn(points);

        // when
        List<Point> usePoints = pointService.usePoint(useRequest);

        // then
        assertThat(usePoints).hasSize(3);
        assertThat(usePoints)
            .extracting(Point::getRemainAmount).containsExactly(0,0,200);

        verify(eventPublisher, times(3)).publishEvent(captor.capture());
    }

    @DisplayName("사용한 포인트를 취소 시, 만료되지 않았다면 복원된다.")
    @Test
    void canUseCancel() {

        // given
        Point usePoint =
            Point.create(new PointCreateRequest(1L, 1000, LocalDateTime.now().plusDays(30), PointRewardType.MANUAL));

        usePoint.use(500);

        PointHistory useHistory =
            PointHistory.create(usePoint, PointHistoryActionType.USE, 500, 1234L, null);

        List<PointHistory> pointHistories = new ArrayList<>();
        pointHistories.add(useHistory);

        PointUseCancelRequest useCancelRequest = new PointUseCancelRequest(1L, 1234L);

        given(pointHistoryRepository.findAllUsageByOrderKey(any())).willReturn(pointHistories);

        // when
        List<Point> cancelPoints = pointService.cancelUsePoint(useCancelRequest);

        // then
        assertThat(cancelPoints).hasSize(1);
        assertThat(cancelPoints)
            .extracting(Point::getRemainAmount).containsExactly(1000);

        verify(eventPublisher).publishEvent(captor.capture());
    }

    @DisplayName("사용한 포인트를 취소 시, 만료된 포인트는 복원하지 않고 새로 적립 한다.")
    @Test
    void recreatePointWhenUseCancelExpiredPoint() {

        // given
        Point expiredPoint = Point.builder()
            .pointKey(1L)
            .userKey(1L)
            .status(PointStatus.EXPIRED)
            .rewardType(PointRewardType.MANUAL)
            .totalAmount(1000)
            .remainAmount(0)
            .expiredDate(LocalDateTime.now().minusDays(1))
            .createdAt(LocalDateTime.now().minusDays(31))
            .build();

        Point activePoint = Point.builder()
            .userKey(1L)
            .status(PointStatus.ACTIVE)
            .rewardType(PointRewardType.MANUAL)
            .totalAmount(1000)
            .remainAmount(1000)
            .expiredDate(LocalDateTime.now().plusDays(365))
            .createdAt(LocalDateTime.now())
            .build();

        PointHistory expiredPointHistory =
            PointHistory.create(expiredPoint, PointHistoryActionType.USE, 1000, 1234L, null);

        ReflectionTestUtils.setField(expiredPointHistory, "historyKey", 10L);

        List<PointHistory> pointHistories = new ArrayList<>();
        pointHistories.add(expiredPointHistory);

        given(pointHistoryRepository.findAllUsageByOrderKey(any())).willReturn(pointHistories);
        given(pointRepository.save(any())).willReturn(activePoint);

        ReflectionTestUtils.setField(activePoint, "pointKey", 2L);

        PointUseCancelRequest request = new PointUseCancelRequest(1L, 1234L);

        // when
        List<Point> cancelPoints = pointService.cancelUsePoint(request);

        // then
        assertThat(cancelPoints).hasSize(1);
        assertThat(cancelPoints).extracting(Point::getPointKey, Point::getRemainAmount)
            .containsExactlyInAnyOrder(
                Tuple.tuple(2L, 1000));

        verify(eventPublisher, times(2)).publishEvent(captor.capture());
    }

    private Point createPoint(Long pointKey, PointRewardType rewardType, int amount, LocalDateTime expiredDate) {
        return Point.builder()
            .pointKey(pointKey)
            .userKey(1L)
            .status(PointStatus.ACTIVE)
            .rewardType(rewardType)
            .totalAmount(amount)
            .remainAmount(amount)
            .expiredDate(expiredDate)
            .build();
    }
}