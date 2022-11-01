package com.concurrency;

import com.hr.Employee;
import org.hibernate.*;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.LockModeType;
import javax.persistence.LockTimeoutException;
import java.util.Collections;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class PessimisticLockingExamples extends HibernateTest {

    private static final Logger log = LoggerFactory.getLogger(PessimisticLockingExamples.class);

    @Override
    DataSourceProvider dataSourceProvider() {
        return new PostgresqlHrDataSourceProvider();
    }

    @Override
    boolean recreateBeforeEachTest() {
        // Reuse the data that came with the database.
        return false;
    }

    @Test
    void pessimisticReadExample() {
        // Check logs to see the SQL statement used by Hibernate.
        doInHibernate(session -> {
            Employee employee = session.get(Employee.class, 100);

            session.buildLockRequest(new LockOptions(LockMode.PESSIMISTIC_READ))
                    .lock(employee);

            log.info("Check logs to see the locking SQL expression.");
        });
    }

    @Test
    void pessimisticWriteExample() {
        // Check logs to see the SQL statement used by Hibernate.
        doInHibernate(session -> {
            Employee employee = session.get(Employee.class, 100);

            session.buildLockRequest(new LockOptions(LockMode.PESSIMISTIC_WRITE))
                    .lock(employee);
        });
    }

    @Test
    void predicateLockingExample() {
        // Check logs to see the SQL statement used by Hibernate.
        doInHibernate(session -> {
            session.createQuery("" +
                            "SELECT e " +
                            "FROM Employee e " +
                            "WHERE e.jobId = 'SA_REP'", Employee.class)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultList();
        });
    }

    @Test
    void predicateLockingNoWaitExample() {
        // Check logs to see the SQL statement used by Hibernate.
        LockOptions lockOptions = new LockOptions(LockMode.PESSIMISTIC_WRITE);
        lockOptions.setTimeOut(LockOptions.NO_WAIT);

        // Check logs to see the SQL statement used by Hibernate.
        doInHibernate(session -> {
            session.find(Employee.class, 1,
                    LockModeType.PESSIMISTIC_WRITE,
                    Collections.singletonMap(
                            AvailableSettings.JPA_LOCK_TIMEOUT,
                            LockOptions.NO_WAIT
                    )
            );
        });
    }

    @Test
    void sharedLockAllowsOtherSharedLock() {
        class Context {
            Employee employee;
        }

        RunnableWithContext<Context> getEmployeeOneWithSharedLockStep = (session, context) -> {
            // Acquire shared lock on Employee #100; use NOWAIT to detect any conflicts right away.
            Employee employee = session.find(Employee.class, 100,
                    LockModeType.PESSIMISTIC_READ,
                    Collections.singletonMap(
                            AvailableSettings.JPA_LOCK_TIMEOUT,
                            LockOptions.NO_WAIT
                    ));
            assertNotNull(employee);
            context.employee = employee;
        };
        RunnableWithContext<Context> checkStep = (session, context) -> {
            assertNotNull(context.employee);
            assertTrue(session.isOpen());
            assertTrue(session.contains(context.employee));
        };

        TwoThreadsWithTransactions<Context> twoThreadsWithTransactions = TwoThreadsWithTransactions.configure(entityManagerFactory, Context::new)
                .threadOneStartsWith(getEmployeeOneWithSharedLockStep)
                .thenThreadTwo(getEmployeeOneWithSharedLockStep)
                .thenThreadOne(checkStep)
                .thenThreadTwo(checkStep)
                .thenFinish();

        twoThreadsWithTransactions.run();
    }

    @Test
    void sharedLockDoesNotAllowsOtherExclusiveLock() {
        class Context {
            Employee employee;
        }

        RunnableWithContext<Context> getEmployeeOneWithSharedLock = (session, context) -> {
            // Acquire shared lock on Employee #100; use NOWAIT to detect any conflicts right away.
            Employee employee = session.find(Employee.class, 100,
                    LockModeType.PESSIMISTIC_READ,
                    Collections.singletonMap(
                            AvailableSettings.JPA_LOCK_TIMEOUT,
                            LockOptions.NO_WAIT
                    ));
            assertNotNull(employee);
            context.employee = employee;
        };

        RunnableWithContext<Context> failAtGettingExclusiveLock = (session, context) -> {
            // Acquire an exclusive lock on Employee #100; use NOWAIT to detect any conflicts right away.
            Executable getExclusiveLock = () -> session.find(Employee.class, 100,
                    LockModeType.PESSIMISTIC_WRITE,
                    Collections.singletonMap(
                            AvailableSettings.JPA_LOCK_TIMEOUT,
                            LockOptions.NO_WAIT
                    ));
            assertThrows(LockTimeoutException.class, getExclusiveLock);
        };

        TwoThreadsWithTransactions<Context> twoThreadsWithTransactions = TwoThreadsWithTransactions.configure(entityManagerFactory, Context::new)
                .threadOneStartsWith(getEmployeeOneWithSharedLock)
                .thenThreadTwo(failAtGettingExclusiveLock)
                .thenFinish();

        twoThreadsWithTransactions.run();
    }

    // todo tests:
    //  1. predicate lock -> blocks update in the same range
    //  2. predicate lock -> blocks delete in the same range
    //  3. predicate lock -> does not block insert in the same range
    //  4. predicate lock -> does not block update in another range
    //  5. predicate lock -> does not block delete in another range

    // In these tests, we need a non-read-only behavior.
    void doInHibernate(Consumer<Session> callable) {
        doInHibernate(callable, false, FlushMode.AUTO);
    }
}
