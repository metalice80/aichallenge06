const TASK_EVENTS = [
    'PLAN_APPROVED',
    'EXECUTION_COMPLETED',
    'VALIDATION_PASSED',
    'VALIDATION_FAILED',
];

export function deriveTaskEventControls(task, busy = false) {
    const allowedEvents = new Set(Array.isArray(task?.allowedEvents) ? task.allowedEvents : []);
    const hasTask = Boolean(task);
    return Object.fromEntries(
        TASK_EVENTS.map((event) => {
            const allowed = allowedEvents.has(event);
            return [event, { hidden: !allowed, disabled: busy || !hasTask || !allowed }];
        }),
    );
}
