import assert from 'node:assert/strict';
import test from 'node:test';
import { deriveTaskEventControls } from '../../main/resources/static/task-controls.mjs';

const events = [
    'PLAN_APPROVED',
    'EXECUTION_COMPLETED',
    'VALIDATION_PASSED',
    'VALIDATION_FAILED',
];

function visibleEvents(controls) {
    return events.filter((event) => !controls[event].hidden);
}

test('controls follow backend allowedEvents instead of local stage', () => {
    const controls = deriveTaskEventControls({
        stage: 'DONE',
        allowedEvents: ['PLAN_APPROVED'],
    });

    assert.deepEqual(visibleEvents(controls), ['PLAN_APPROVED']);
    assert.equal(controls.PLAN_APPROVED.disabled, false);
});

test('validation exposes exactly both events returned by backend', () => {
    const controls = deriveTaskEventControls({
        stage: 'PLANNING',
        allowedEvents: ['VALIDATION_PASSED', 'VALIDATION_FAILED'],
    });

    assert.deepEqual(visibleEvents(controls), ['VALIDATION_PASSED', 'VALIDATION_FAILED']);
});

test('empty or missing allowedEvents hides every transition control', () => {
    assert.deepEqual(visibleEvents(deriveTaskEventControls({ stage: 'EXECUTION', allowedEvents: [] })), []);
    assert.deepEqual(visibleEvents(deriveTaskEventControls({ stage: 'EXECUTION' })), []);
    assert.deepEqual(visibleEvents(deriveTaskEventControls(null)), []);
});

test('busy state disables but does not hide backend-allowed controls', () => {
    const controls = deriveTaskEventControls(
        { allowedEvents: ['EXECUTION_COMPLETED'] },
        true,
    );

    assert.deepEqual(visibleEvents(controls), ['EXECUTION_COMPLETED']);
    assert.equal(controls.EXECUTION_COMPLETED.disabled, true);
});
