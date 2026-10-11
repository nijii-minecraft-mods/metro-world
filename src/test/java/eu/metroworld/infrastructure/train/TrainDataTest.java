package eu.metroworld.infrastructure.train;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class TrainDataTest {
    @Test
    void defaultCarLengthsFallbackToTrainCarEnum() {
        TrainData data = new TrainData();
        data.cars.addAll(List.of(TrainCar.LOCOMOTIVE, TrainCar.CONTAINER));
        assertEquals(TrainCar.LOCOMOTIVE.length, data.carLength(0));
        assertEquals(TrainCar.CONTAINER.length, data.carLength(1));
        assertEquals(TrainCar.LOCOMOTIVE.length + TrainCar.CONTAINER.length + 1, data.length());
    }

    @Test
    void customLengthsTakePrecedenceOverEnumDefaults() {
        TrainData data = new TrainData();
        data.cars.addAll(List.of(TrainCar.METRO_BLUE, TrainCar.METRO_BLUE));
        data.lengths.addAll(List.of(12.5, 14.0));
        assertEquals(12.5, data.carLength(0));
        assertEquals(14.0, data.carLength(1));
        assertEquals(12.5 + 14.0 + 1, data.length());
    }

    @Test
    void modelAtAndScaleAtFallbackSafely() {
        TrainData data = new TrainData();
        data.model = "default_model";
        data.modelScale = 0.8;
        assertEquals("default_model", data.modelAt(0));
        assertEquals(0.8, data.scaleAt(0));
        assertEquals(0, data.modelIndex(0));

        data.modelIds.add("custom_car_0");
        data.modelIndices.add(5);
        data.modelScales.add(0.95);
        assertEquals("custom_car_0", data.modelAt(0));
        assertEquals(5, data.modelIndex(0));
        assertEquals(0.95, data.scaleAt(0));
        assertEquals("default_model", data.modelAt(1));
    }
}
