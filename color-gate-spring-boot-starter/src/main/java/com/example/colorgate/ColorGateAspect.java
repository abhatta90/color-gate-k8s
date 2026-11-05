package com.example.colorgate;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Skips {@code @Scheduled} methods (unless {@link RunInAllColors}) and {@link ColorGated} methods while
 * this pod is passive. The schedule keeps ticking; a passive tick just does nothing.
 */
@Aspect
public class ColorGateAspect {

    private static final Logger log = LoggerFactory.getLogger(ColorGateAspect.class);

    // Looked up lazily so creating the aspect does not force early creation of the gate.
    private final ObjectProvider<ColorGate> gate;

    public ColorGateAspect(ObjectProvider<ColorGate> gate) {
        this.gate = gate;
    }

    @Around("(@annotation(org.springframework.scheduling.annotation.Scheduled)"
            + " || @annotation(org.springframework.scheduling.annotation.Schedules))"
            + " && !@annotation(com.example.colorgate.RunInAllColors)"
            + " && !@within(com.example.colorgate.RunInAllColors)")
    public Object gateScheduled(ProceedingJoinPoint pjp) throws Throwable {
        return proceedIfActive(pjp);
    }

    @Around("@annotation(com.example.colorgate.ColorGated) || @within(com.example.colorgate.ColorGated)")
    public Object gateAnnotated(ProceedingJoinPoint pjp) throws Throwable {
        return proceedIfActive(pjp);
    }

    private Object proceedIfActive(ProceedingJoinPoint pjp) throws Throwable {
        ColorGate colorGate = gate.getObject();
        if (colorGate.isActive()) {
            return pjp.proceed();
        }
        log.debug("Color gate: {} is passive, skipping {}", colorGate.getMyColor(), pjp.getSignature().toShortString());
        return null;
    }
}
