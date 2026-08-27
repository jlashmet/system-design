# Comprehensive Testing Guide

## Introduction

This document provides a comprehensive guide to testing practices for our codebase. It combines our established testing structure and conventions with guidance on when to use different testing approaches like fakes and mocks. Following these guidelines will help ensure our tests are consistent, maintainable, and effective at catching issues.

## Current Domain Model Challenges

Without a proper domain model, we face several challenges in our testing and development:

- **Layer Segment Validation**: We don't know which layer segments are valid or which are actually used in production. This leads to inconsistent data and validation issues.
- **Transaction Status Variations**: We have multiple variations of transaction status values like "P", "Pending", "PENDING", etc., without a clear canonical form. This creates confusion and inconsistent handling across the codebase.
- **Production Data Inconsistencies**: The lack of domain model constraints means production data can have unexpected variations that break our assumptions.

These issues highlight the importance of having a well-defined domain model with proper validation and constraints.

## Testing Philosophy and Principles

Our testing approach is guided by the following principles:

1. **Test behavior, not implementation** - Tests should verify what the code does, not how it does it
2. **Maintainability** - Tests should be easy to understand and maintain
3. **Reliability** - Tests should provide consistent results
4. **Readability** - Tests should clearly express their intent
5. **Resilience to refactoring** - Tests should not break when implementation details change

## Test Structure and Conventions

### Given/When/Then Format

All tests should follow the Given/When/Then format to promote consistency and readability:

- **Given**: Sets up the initial state for the test
- **When**: Executes the action being tested
- **Then**: Verifies the expected outcome

Each test must contain exactly 3 statements: one call to `given`, one call to `whenXXX`, and one call to `thenExpect`.

**No setup code, variable declarations, or other logic should appear between these three method calls.** All setup logic must be encapsulated within the `given`, `when`, and `thenExpect` methods themselves.

### ❌ INCORRECT - Setup code between method calls:
```java
@Test
void testSomeFeature() {
    given(initialData());

    // ❌ This setup code should NOT be here
    SomeObject obj = new SomeObject();
    obj.setSomeProperty("value");

    whenSomeAction(obj);

    // ❌ This setup code should NOT be here either
    ExpectedResult expected = new ExpectedResult();
    expected.setExpectedValue("result");

    thenExpect(expected);
}
```

### ✅ CORRECT - Exactly 3 statements:
```java
@Test
void testSomeFeature() {
    given(initialData());
    whenSomeAction(inputData());
    thenExpect(expectedResult());
}
```

```java
@Test
void testCreateTransactions() {
    given(
        transactions(
            posted(transactionId1.toString(), "fitid1", _01_01_2025),
            posted(transactionId2.toString(), "fitid2", _02_01_2025)
        )
    );

    whenCreateTransactions(
        transactions(
            posted(transactionId1.toString(), "fitid1", _01_01_2025),
            posted(transactionId2.toString(), "fitid2", _02_01_2025)
        )
    );

    thenExpect(
        transactions(
            posted(transactionId1.toString(), "fitid1", _01_01_2025),
            posted(transactionId2.toString(), "fitid2", _02_01_2025)
        ),
        List.of()
    );
}
```

### Given Methods

- Should take builders as input (not direct objects)
- Should initialize the Subject Under Test (SUT) and inject all collaborators
- Exactly one `given` method per test
- Use `given()` when there is no starting state
- Should be named "given"
- Use `lenient()` for mock stubs
- **Parameters MUST be self-documenting** - The meaning of each parameter should be clear at the call site

#### ❌ INCORRECT - Opaque, unreadable parameters:
```java
// What do true, true, 100 mean? No idea without reading the helper method!
given(true, true, 100, testData.createEntityResolutionOfferingIds("1", "2", "3"));
```

#### ✅ CORRECT - Self-documenting parameters:
```java
// Option 1: Use named builders or factory methods that describe the configuration
given(entityResolutionConfig()
    .withChunkingEnabled(true)
    .withErMlEnabled(true)
    .withChunkSize(100)
    .withOfferingIds("1", "2", "3"));

// Option 2: Use typed enums or value objects
given(ChunkingMode.ENABLED, ErMlMode.ENABLED, ChunkSize.of(100), offeringIds("1", "2", "3"));
```

### When Methods

- **MUST execute a method on the Subject Under Test (SUT)** - This is the core purpose of the `when*` method
- Should be created for each SUT method being tested
- Should take builders as parameters
- Parameters should match those sent to the method being tested
- Should set a field with the actual result
- Should be named "whenXXX" based on the method being tested

#### ❌ INCORRECT - when* method that doesn't call the SUT:
```java
// This is FORBIDDEN - the when* method just stores expected values, doesn't call the SUT!
private void whenConfigExecuted(boolean expChunkingEnabled, boolean expErMlEnabled, int expChunkSize, int expOfferingIdsSize) {
    this.expectedChunkingEnabled = expChunkingEnabled;  // ❌ Just storing values!
    this.expectedErMlEnabled = expErMlEnabled;
    this.expectedChunkSize = expChunkSize;
    this.expectedOfferingIdsSize = expOfferingIdsSize;
}
```

#### ✅ CORRECT - when* method that calls the SUT:
```java
// The when* method MUST call a method on the Subject Under Test
private void whenConfigExecuted() {
    entityConfig = getDefaultConfiguration();  // ✅ Calls method on SUT
}

private void whenProcessTransaction() {
    result = processor.process(transaction);  // ✅ Calls method on SUT
}

private void whenCreateTransactions(List<TransactionBuilder> builders) {
    response = transactionService.create(builders);  // ✅ Calls method on SUT
}
```

### ThenExpect Methods

- Should take builders as input
- Should use the reflective assert from AssertJ
- Exactly one `thenExpect` method per return type of the SUT methods
- Should be named "thenExpect"
- **Expectations MUST be visible at the call site** - What is being verified should be clear from the test method itself
- Should always take the expected object or values as parameters (typically builders) so expectations are visible at the call site
- Avoid zero-argument `thenExpect` methods that hide important expected data inside helper methods; the data that makes the test pass or fail must be visible in the `given(...)`, `when...(...)`, and `thenExpect(...)` arguments
- **Always prefer varargs over `List.of()`** for cleaner test code (applies to both unit tests and functional tests)

#### ❌ INCORRECT - Opaque thenExpect that hides what's being verified:
```java
// What is being verified here? No idea without reading the helper method!
thenExpect(config);

// The helper method hides all the actual expectations:
private void thenExpect(EntityResolutionConfig actualConfig) {
    assertEquals(expectedChunkingEnabled, actualConfig.isChunkingEnabled());
    assertEquals(expectedErMlEnabled, actualConfig.isErMlEnabled());
    // ... where do expectedChunkingEnabled etc come from? Stored in the when* method!
}
```

#### ✅ CORRECT - Expectations are clear at the call site:
```java
// Option 1: Pass expected values directly
thenExpect(chunkingEnabled(true), erMlEnabled(true), chunkSize(100), offeringCount(3));

// Option 2: Use a builder that shows what's expected
thenExpect(entityResolutionConfig()
    .withChunkingEnabled(true)
    .withErMlEnabled(true)
    .withChunkSize(100)
    .withOfferingIds("1", "2", "3"));

// Option 3: For simple unit tests, use the expected values directly
thenExpect(true, true, 100, 3);  // Only if the parameter names are obvious from context
```

#### Expectation Records/Classes

**For unit tests**: Keep `thenExpect` methods simple. Use primitive types, strings, or the domain objects directly as parameters. Do NOT create custom Expectation records/classes - this adds unnecessary ceremony for simple unit tests.

```java
// ✅ CORRECT for unit tests - simple parameters
thenExpect("expected-value");
thenExpect(expectedAccount());

// ❌ INCORRECT for unit tests - unnecessary Expectation records
private record ExceptionExpectation(String message) {}
thenExpect(new ExceptionExpectation("error"));
```

**For functional/integration tests**: Expectation records/classes are acceptable when tests have complex verification requirements spanning multiple assertions or when the same expectation pattern is reused across many tests.

#### Exception Testing

For both unit tests and functional tests, exception expectations should include the exception type and any relevant inner parameters (e.g., error codes, message substrings):

```java
// ✅ CORRECT - exception type with relevant parameters
thenExpect(IllegalArgumentException.class, "Invalid account ID");
thenExpect(ValidationException.class, ErrorCode.INVALID_FORMAT);

// For functional tests - verify response codes in the body
thenExpect(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR);
```

#### ❌ INCORRECT - Using List.of():
```java
thenExpect(List.of(transaction(), transaction()));
```

#### ✅ CORRECT - Using varargs:
```java
thenExpect(transaction(), transaction());
```

- If multiple lists are required, use a static builder

#### ❌ INCORRECT - Using List.of():
```java
thenExpect(List.of(transaction(), transaction()), List.of(transaction(), transaction()));
```

#### ✅ CORRECT - Using varargs:
```java
thenExpect(transactions(transaction(), transaction()), transactions(transaction(),transaction()));
```

### ❌ FORBIDDEN - String Scenario Switch Patterns

NEVER use `given(String scenario)` or `thenExpect(String scenario)` with switch statements. This anti-pattern:
- Hides what the test is actually setting up or verifying
- Makes tests unreadable at the call site
- Requires looking inside the base class to understand what's happening
- Violates the principle that test expectations should be visible at the call site

#### ❌ INCORRECT - String scenario switch pattern:
```java
// In base class - FORBIDDEN
protected void given(String scenario) {
    switch (scenario) {
        case EMPTY_TRANSACTION_LIST:
            givenEmptyTransactionList();
            break;
        case JSON_PROCESSING_ERROR:
            givenJsonProcessingError();
            break;
        // ... many more cases
    }
}

void thenExpect(String scenario) {
    switch (scenario) {
        case EDGE_CASES:
            // verification logic hidden here
            break;
        case CONVERSION_TO_MAP:
            // different verification hidden here
            break;
    }
}

// In test - unreadable, what does EDGE_CASES even mean?
@Test
public void testNullProviderTxnLayerForUser() {
    given("Completed", AuthType.USER, false, false);
    whenGenerateRequestExecuted();
    thenExpect(EDGE_CASES);  // ❌ What is being verified? No idea!
}
```

#### ✅ CORRECT - Explicit parameters:
```java
// In base class - clear typed methods
protected void given(Transaction transaction, AuthContext authContext) {
    this.transaction = transaction;
    this.authContext = authContext;
}

void thenExpect(CategorizationRequest expectedRequest) {
    StepVerifier.create(actualRequest)
        .assertNext(request -> assertThat(request).isEqualTo(expectedRequest))
        .verifyComplete();
}

// In test - immediately clear what's set up and verified
@Test
public void testCategorizationRequestWithCompletedTransaction() {
    given(completedTransaction(), userAuthContext());
    whenGenerateRequestExecuted();
    thenExpect(categorizationRequestFor(completedTransaction()));
}
```

The goal is that **anyone reading the test method should understand what's being tested without looking at helper methods**. The `given()`, `when*()`, and `thenExpect()` calls should be self-documenting.

## Types of Tests

### Unit Tests

- Test individual components in isolation
- Dependencies should be mocked with Mockito
- Avoid mocking static methods (refactor if necessary)
- Focus on testing a single unit of functionality

### Integration Tests

- Test how components work together
- Do not mock any depending Domain/Infrastructure classes for processors
- Use `@SpringJUnitConfig` annotation to enable spring context management
- Define in-memory implementations for I/O or network calling clients
- Do not use SpringBoot in the tests

### Functional Tests

- Test the entire system from end to end
- Use the canonical model (not the domain model)
- Assertions should be limited to outputs of the pipeline
- Tests should not use any code from within the processors

### Performance Tests

- Test system performance under load
- Focus on response times, throughput, and resource usage
- Measure p50, p95/p99, and max latencies
- Test with 50-80% of expected peak traffic

## Test Doubles: Fakes vs Mocks

### Definitions

**Mocks** are objects that register calls they receive and allow you to set up expectations about how they should be used. They focus on behavior verification - checking that the correct methods were called with the right parameters.

**Fakes** are working implementations of interfaces that are simplified for testing purposes. Unlike mocks, fakes have actual working business logic, albeit simplified. They don't verify interactions but instead provide a lightweight alternative to the real implementation.

### Why We Prefer Fakes Over Mocks

1. **Fakes Test Behavior, Not Implementation** - Fakes allow us to test the behavior of our system rather than its implementation details
2. **Fakes Lead to More Realistic Tests** - Tests with fakes more closely resemble how the code will behave in production
3. **Fakes Improve Test Readability** - Tests using fakes tend to be more readable and express intent more clearly
4. **Fakes Support Better Refactoring** - Fakes are more resilient to refactoring since they focus on the interface rather than implementation details
5. **Fakes Catch More Issues** - Because fakes implement actual logic, they can catch issues that mocks might miss

### When to Use Fakes

Use fakes when:

1. **Testing complex interactions** - When the component under test interacts with dependencies in complex ways
2. **Testing stateful behavior** - When the dependency maintains state that affects the behavior of the system
3. **Integration testing** - When testing how components work together
4. **Testing asynchronous code** - Fakes can simulate asynchronous behavior more realistically
5. **Long-term test maintenance** - For tests that need to be maintained over a long period

### When to Use Mocks

Despite our preference for fakes, mocks are still valuable in certain scenarios:

1. **Verifying specific interactions** - When you need to verify that specific methods were called with specific parameters
2. **Testing error handling** - When you need to simulate error conditions from dependencies
3. **Testing rare conditions** - When you need to test conditions that are difficult to trigger with real implementations
4. **Performance-critical tests** - When using real implementations would make tests too slow
5. **Third-party services** - When interacting with external services that are difficult to fake

## Best Practices

### Test Data Management

- Create TestData classes for constants and helper methods
- TestData classes should be named `<Entity>TestData`
- Methods and constants should be imported statically from tests
- For domain entities with builders (e.g., `Account`), tests should not call `Account.builder()` directly; instead add a TestData helper like `account()` that returns the builder and import it statically.
- TestData classes should reside in a dedicated package
- Each TestData helper should build only a single level of the object graph; deeper levels should be built in their own TestData classes and composed in the tests
- Avoid verbose, scenario-specific TestData method names (e.g. `createDomainBalanceWithNullTimestamp`); instead, create focused TestData classes per value/object type (e.g. `MoneyTestData`, `DomainBalanceTestData`) and compose them in tests.

### Unique ID Generation

When a test requires unique IDs (e.g., profile IDs, account IDs) that need to be used multiple times across `given()`, `when*()`, and `thenExpect()` calls, **leverage existing TestData generator methods** and declare them as instance fields:

```java
import static com.example.testdata.ProfileTestData.newProfileId;
import static com.example.testdata.AccountTestData.newAccountId;

public class MyFeatureIT {
    // Use TestData generators for unique IDs
    private final String profileId1 = newProfileId();
    private final String accountId1 = newAccountId();

    @Test
    void testSomeFeature() {
        given(profileResponse(profileId1).withAccount(accountId1));
        whenGetProfile(profileId1);
        thenExpect(profileResponse(profileId1).withAccount(accountId1));
    }
}
```

This pattern:
- **Leverages existing TestData classes** - Use `newProfileId()`, `newAccountId()`, `newTransactionId()`, etc. from the appropriate TestData class
- Keeps ID generation outside of `given()`/`when*()`/`thenExpect()` statements
- Makes IDs available to all three method calls without inline generation
- Uses clear naming (`profileId1`, `accountId2`) to indicate the role and instance number

**Do not**:
- Generate IDs inline with `System.nanoTime()` or `UUID.randomUUID()` - use TestData generators instead
- Use helper methods that generate and store IDs within `given()` calls - this obscures the test setup

```java
public class TransactionTestData {
    public static PostedTransaction.PostedTransactionBuilder posted(
            String providerId,
            ProfileId profileId,
            AccountId accountId) {
        return PostedTransaction.builder()
            .accountId(accountId)
            .profileId(profileId)
            .postedDate(_01_01_2025)
            .createdDate(_03_01_2025)
            .amount(20.00)
            .description("Amazon purchase")
            .id(TransactionIdTestData.newTransactionId())
            .providerId(providerId)
            .status(TransactionStatus.POSTED);
    }

    @SafeVarargs
    public static List<Transaction.TransactionBuilder<?, ?>> transactions(
            Transaction.TransactionBuilder<?, ?>... builders) {
        return Arrays.asList(builders);
    }
}
```

### Builder Pattern

- Each domain entity & value object should leverage the lombok `@Builder` annotation
- Builders should be used within the domain objects themselves to create instances
- Builders may be customized by defining a static inner class explicitly
- Customized builder methods may take other builders as input (such methods should be named "with")

```java
public class TransactionEventTestData {
    public static TransactionCreatedEvent transactionCreatedEvent(
            Transaction.TransactionBuilder<?, ?> transactionBuilder) {
        return new TransactionCreatedEvent(transactionBuilder.build());
    }

    public static TransactionPostedDateChangedEvent transactionPostedDateChangedEvent(
            Transaction.TransactionBuilder<?, ?> transactionBuilder,
            Date oldPostedDate) {
        return new TransactionPostedDateChangedEvent(transactionBuilder.build(), oldPostedDate);
    }
}
```

### Transaction Builder Pattern

For creating transaction test data, use builder methods that provide a fluent interface for constructing FDP Transaction objects with layers and segments:

```java
Transaction testTransaction = Transaction.builder()
    .transactionId("test-txn-123")
    .associations(Associations.of("urn:profile:123", "urn:account:456"))
    .layers(List.of(
        TransactionLayer.builder()
            .layerId("L10_PROVIDER")
            .layerSegments(List.of(
                TransactionLayerSegment.builder()
                    .layerSegmentId("L10_LS10_providerId:provider123")
                    .attributes(Map.of(
                        "amount", 100.0,
                        "description", "Test transaction",
                        "status", "POSTED"
                    ))
                    .build()
            ))
            .build(),
        TransactionLayer.builder()
            .layerId("L20_PLATFORM")
            .layerSegments(List.of(
                TransactionLayerSegment.builder()
                    .layerSegmentId("L20_LS10_platform")
                    .attributes(Map.of(
                        "createdTimestamp", new Date(),
                        "lastModifiedTimestamp", new Date()
                    ))
                    .build()
            ))
            .build()
    ))
    .build();
```

The builder pattern supports:
- Layer creation with `TransactionLayer.builder()`
- Segment creation with `TransactionLayerSegment.builder()`
- Property setting with the `attributes()` method
- Association and alternate ID management

### Assertions

- Use assertJ reflective comparison to verify the entire expected state
- Do not perform assertions on individual fields/data
- Prefer varargs to Set.of/List.of for multiple readability

```java
public void assertTransactionsEqual(List<Transaction> actual, List<Transaction> expected) {
    assertThat(actual)
        .usingRecursiveComparison()
        .withComparatorForType(dateComparator, Date.class)
        .ignoringCollectionOrder()
        .ignoringAllOverriddenEquals()
        .ignoringFields("dedupeResult", "id", "domainEvents", "attributes", "createdDate")
        .isEqualTo(expected);
}
```

### Creating Fakes

In our codebase, we typically create fakes by:

1. Implementing the same interface/protocol as the real component
2. Using data classes for simple configuration
3. Providing simplified but functional implementations

```java
public class InMemoryTransactionRepository implements TransactionRepository {
    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();

    @Override
    public void save(List<Transaction> transactions) {
        transactions.forEach(transaction ->
            this.transactions.put(transaction.getId().toString(), transaction)
        );
    }

    @Override
    public PagedResult<Transaction> searchByAccountAndPostedDate(
            ProfileId profileId,
            AccountId accountId,
            Date startDate,
            Date endDate) {
        List<Transaction> filtered = transactions.values().stream()
            .filter(transaction ->
                transaction.getAccountId().equals(accountId) &&
                !transaction.getPostedDate().before(startDate) &&
                !transaction.getPostedDate().after(endDate)
            )
            .toList();
        return new PagedResult<>(filtered, filtered.size(), 0, filtered.size());
    }
}
```

## Do's and Don'ts

### Use Given/When/ThenExpect format

**Do:**
```java
@Test
void testLayerValidationCorrection() {
    given(
        Transaction.builder()
            .layers(List.of(
                TransactionLayer.builder()
                    .layerId("L10_PROVIDER")
                    .layerSegments(List.of(
                        TransactionLayerSegment.builder()
                            .layerSegmentId("L10_LS10_PROVIDERID:test123")
                            .build()
                    ))
                    .build()
            ))
            .build()
    );

    whenValidateAndCorrect();

    thenExpect(Layers.L10_PROVIDER_ACTUAL_CASE, "L10_LS10_providerId:test123");
}
```

**Don't:**
```java
@Test
void testLayerValidationCorrection() {
    Transaction transaction = createTransaction();
    LayerValidator.validateAndCorrectTransaction(transaction);
    assertEquals("L10_LS10_providerId:test123",
        transaction.getLayers().get(0).getLayerSegments().get(0).getLayerSegmentId());
}
```

### Ensure parameters are clear upon first glance

**Do:**
```java
@Test
void testTransactionWithSpecificDates() {
    given(
        transactions(
            posted(transactionId1.toString(), "fitid1", _01_01_2025),
            posted(transactionId2.toString(), "fitid2", _02_01_2025)
        )
    );

    whenCreateTransactions(
        transactions(
            posted(transactionId3.toString(), "fitid3", _03_01_2025)
        )
    );

    thenExpect(
        transactions(
            posted(transactionId1.toString(), "fitid1", _01_01_2025),
            posted(transactionId2.toString(), "fitid2", _02_01_2025),
            posted(transactionId3.toString(), "fitid3", _03_01_2025)
        ),
        List.of(transactionCreatedEvent(posted(transactionId3.toString(), "fitid3", _03_01_2025)))
    );
}
```

**Don't:**
```java
@Test
void testTransactionWithSpecificDates() {
    given(transactions(posted("id1", "fitid1", new Date()), posted("id2", "fitid2", null)));
    whenCreateTransactions(transactions(posted("id3", "fitid3", new Date())));
    thenExpect(transactions(posted("id1", "fitid1", new Date()), posted("id2", "fitid2", null)), List.of());
}
```

### Use static imports when referencing "TestData" classes

**Do:**
```java
@Test
void testTransactionEventCreation() {
    given(
        transactions(
            posted(PROVIDER_ID_1, userId1, accountId1),
            pending(PROVIDER_ID_2, userId1, accountId1)
        )
    );

    whenCreateTransactions(
        transactions(
            posted(PROVIDER_ID_1, userId1, accountId1)
        )
    );

    thenExpect(
        transactions(posted(PROVIDER_ID_1, userId1, accountId1)),
        List.of(transactionCreatedEvent(posted(PROVIDER_ID_1, userId1, accountId1)))
    );
}
```

**Don't:**
```java
@Test
void testTransactionEventCreation() {
    given(
        TransactionTestData.transactions(
            TransactionTestData.posted(ProviderTestData.PROVIDER_ID_1, userId1, accountId1),
            TransactionTestData.pending(ProviderTestData.PROVIDER_ID_2, userId1, accountId1)
        )
    );

    whenCreateTransactions(
        TransactionTestData.transactions(
            TransactionTestData.posted(ProviderTestData.PROVIDER_ID_1, userId1, accountId1)
        )
    );

    thenExpect(
        TransactionTestData.transactions(TransactionTestData.posted(ProviderTestData.PROVIDER_ID_1, userId1, accountId1)),
        List.of(TransactionCreatedEventTestData.transactionCreatedEvent(TransactionTestData.posted(ProviderTestData.PROVIDER_ID_1, userId1, accountId1)))
    );
}
```

## Conclusion

By following these testing guidelines, we can create tests that are:

1. **Consistent** - Following the same structure and conventions
2. **Maintainable** - Easy to understand and modify
3. **Effective** - Catching issues early and providing confidence in our code
4. **Resilient** - Not breaking when implementation details change

Remember that the goal of testing is not just to verify that the code works, but to provide a safety net that allows us to confidently refactor and improve our codebase over time.
