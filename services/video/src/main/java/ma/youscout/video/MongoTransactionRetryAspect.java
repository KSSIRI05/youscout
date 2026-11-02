package ma.youscout.video;

import com.mongodb.MongoException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Retries an entire Mongo transaction when MongoDB marks its snapshot as transient. */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MongoTransactionRetryAspect {
    private static final int MAX_ATTEMPTS = 4;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object retryTransientTransaction(ProceedingJoinPoint invocation) throws Throwable {
        Transactional transactional = ((MethodSignature) invocation.getSignature()).getMethod()
                .getAnnotation(Transactional.class);
        if (!"mongoTransactionManager".equals(transactional.transactionManager())
                || transactional.propagation() == Propagation.MANDATORY) {
            return invocation.proceed();
        }

        for (int attempt = 1; ; attempt++) {
            try {
                return invocation.proceed();
            } catch (Throwable failure) {
                if (attempt >= MAX_ATTEMPTS || !isTransientTransaction(failure)) throw failure;
                try {
                    Thread.sleep(10L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
            }
        }
    }

    private boolean isTransientTransaction(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongo
                    && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) return true;
        }
        return false;
    }
}
