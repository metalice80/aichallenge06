const chatForm = document.querySelector('#chat-form');
const chatHistory = document.querySelector('#chat-history');
const emptyState = document.querySelector('#empty-state');
const messageInput = document.querySelector('#message-input');
const providerSelect = document.querySelector('#provider-select');
const modelInput = document.querySelector('#model-input');
const contextStrategySelect = document.querySelector('#context-strategy-select');
const taskSelect = document.querySelector('#task-select');
const createTaskButton = document.querySelector('#create-task-button');
const completeTaskButton = document.querySelector('#complete-task-button');
const profileSelect = document.querySelector('#profile-select');
const createProfileButton = document.querySelector('#create-profile-button');
const editProfileButton = document.querySelector('#edit-profile-button');
const profileDialog = document.querySelector('#profile-dialog');
const profileForm = document.querySelector('#profile-form');
const profileDialogTitle = document.querySelector('#profile-dialog-title');
const profileNameInput = document.querySelector('#profile-name-input');
const profileLanguageSelect = document.querySelector('#profile-language-select');
const profileExpertiseSelect = document.querySelector('#profile-expertise-select');
const profileStyleSelect = document.querySelector('#profile-style-select');
const profileFormatSelect = document.querySelector('#profile-format-select');
const profileCustomInstructions = document.querySelector('#profile-custom-instructions');
const cancelProfileButton = document.querySelector('#cancel-profile-button');
const branchControls = document.querySelector('#branch-controls');
const branchSelect = document.querySelector('#branch-select');
const createBranchButton = document.querySelector('#create-branch-button');
const sendButton = document.querySelector('#send-button');
const resetButton = document.querySelector('#reset-button');
const clearWorkingButton = document.querySelector('#clear-working-button');
const clearLongTermButton = document.querySelector('#clear-long-term-button');
const loadingMessage = document.querySelector('#loading-message');
const errorMessage = document.querySelector('#error-message');
const statsPanel = document.querySelector('#stats-panel');
const shortTermMeta = document.querySelector('#short-term-meta');
const shortTermMemory = document.querySelector('#short-term-memory');
const workingMemory = document.querySelector('#working-memory');
const longTermMemory = document.querySelector('#long-term-memory');
const lastMemoryUpdate = document.querySelector('#last-memory-update');
const effectiveContext = document.querySelector('#effective-context');

let busy = false;
let activeTaskStatus = 'ACTIVE';
let profiles = [];
let editingProfileId = null;

chatForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (busy || activeTaskStatus === 'COMPLETED') return;

    const message = messageInput.value.trim();
    if (!message) {
        showError('Введите сообщение.');
        messageInput.focus();
        return;
    }
    const provider = providerSelect.value;
    const model = modelInput.value.trim();
    const contextStrategy = contextStrategySelect.value;
    if (!provider) {
        showError('Выберите provider.');
        providerSelect.focus();
        return;
    }
    if (!model) {
        showError('Введите model id.');
        modelInput.focus();
        return;
    }
    if (!contextStrategy) {
        showError('Выберите context strategy.');
        contextStrategySelect.focus();
        return;
    }

    clearError();
    const userElement = appendMessage('user', 'Вы', message);
    messageInput.value = '';
    setBusy(true);

    try {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message, provider, model, contextStrategy }),
        });
        const payload = await readJson(response);
        if (!response.ok) {
            throw new Error(payload?.message || 'Не удалось получить ответ от модели.');
        }
        if (typeof payload?.content !== 'string') {
            throw new Error('Сервер вернул некорректный ответ.');
        }

        appendMessage('agent', 'Агент', payload.content);
        updateStats(payload);
        await loadMemory();
    } catch (error) {
        userElement.remove();
        restoreEmptyStateIfNeeded();
        messageInput.value = message;
        showError(error instanceof Error ? error.message : 'Не удалось получить ответ от модели.');
    } finally {
        setBusy(false);
        messageInput.focus();
    }
});

resetButton.addEventListener('click', async () => {
    if (busy) return;
    await runAction(async () => {
        const response = await fetch('/api/chat/reset', { method: 'POST' });
        if (!response.ok) {
            const payload = await readJson(response);
            throw new Error(payload?.message || 'Не удалось сбросить чат.');
        }
        chatHistory.replaceChildren(emptyState);
        emptyState.hidden = false;
        resetCurrentStats();
        updateConversationStats({ inputTokens: 0, outputTokens: 0, totalTokens: 0 });
        statsPanel.hidden = false;
        messageInput.value = '';
        await loadBranches();
        await loadMemory();
    }, 'Не удалось сбросить чат.');
});

clearWorkingButton.addEventListener('click', async () => {
    if (busy || !window.confirm('Очистить рабочую память активной Task?')) return;
    await runAction(async () => {
        await postNoContent('/api/chat/memory/working/clear', 'Не удалось очистить рабочую память.');
        await loadMemory();
    }, 'Не удалось очистить рабочую память.');
});

clearLongTermButton.addEventListener('click', async () => {
    if (busy || !window.confirm('Очистить глобальную долговременную память для всех Tasks?')) return;
    await runAction(async () => {
        await postNoContent('/api/chat/memory/long-term/clear', 'Не удалось очистить долговременную память.');
        await loadMemory();
    }, 'Не удалось очистить долговременную память.');
});

createTaskButton.addEventListener('click', async () => {
    if (busy) return;
    const name = window.prompt('Название новой задачи:')?.trim();
    if (!name) return;
    await runAction(async () => {
        const response = await fetch('/api/chat/tasks', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name }),
        });
        const payload = await readJson(response);
        if (!response.ok) {
            throw new Error(payload?.message || 'Не удалось создать задачу.');
        }
        renderChatState(payload);
        await loadTasks();
        await loadBranches();
        await loadMemory();
    }, 'Не удалось создать задачу.');
});

completeTaskButton.addEventListener('click', async () => {
    if (busy || !taskSelect.value || activeTaskStatus === 'COMPLETED') return;
    if (!window.confirm('Завершить активную задачу? История и память сохранятся.')) return;
    await runAction(async () => {
        const response = await fetch(`/api/chat/tasks/${encodeURIComponent(taskSelect.value)}/complete`, {
            method: 'POST',
        });
        const payload = await readJson(response);
        if (!response.ok) {
            throw new Error(payload?.message || 'Не удалось завершить задачу.');
        }
        await loadTasks();
    }, 'Не удалось завершить задачу.');
});

taskSelect.addEventListener('change', async () => {
    if (busy || !taskSelect.value) return;
    await runAction(async () => {
        const response = await fetch(`/api/chat/tasks/${encodeURIComponent(taskSelect.value)}/activate`, {
            method: 'POST',
        });
        const payload = await readJson(response);
        if (!response.ok) {
            throw new Error(payload?.message || 'Не удалось переключить задачу.');
        }
        renderChatState(payload);
        await loadTasks();
        await loadBranches();
        await loadMemory();
    }, 'Не удалось переключить задачу.');
});

profileSelect.addEventListener('change', async () => {
    if (busy || !profileSelect.value) return;
    await runAction(async () => {
        const response = await fetch(
            `/api/chat/profiles/${encodeURIComponent(profileSelect.value)}/activate`,
            { method: 'POST' },
        );
        const payload = await readJson(response);
        if (!response.ok) {
            throw new Error(payload?.message || 'Не удалось переключить профиль.');
        }
        await loadProfiles();
        await loadMemory();
    }, 'Не удалось переключить профиль.');
});

createProfileButton.addEventListener('click', () => {
    if (!busy) openProfileDialog(null);
});

editProfileButton.addEventListener('click', () => {
    if (busy) return;
    const profile = profiles.find((item) => String(item.id) === profileSelect.value);
    if (profile) openProfileDialog(profile);
});

cancelProfileButton.addEventListener('click', () => profileDialog.close());

profileForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (busy) return;
    const body = {
        name: profileNameInput.value.trim(),
        responseLanguage: profileLanguageSelect.value,
        expertiseLevel: profileExpertiseSelect.value,
        responseStyle: profileStyleSelect.value,
        responseFormat: profileFormatSelect.value,
        customInstructions: profileCustomInstructions.value.trim(),
    };
    if (!body.name) {
        showError('Введите название профиля.');
        profileNameInput.focus();
        return;
    }
    const profileId = editingProfileId;
    await runAction(async () => {
        const response = await fetch(
            profileId === null
                ? '/api/chat/profiles'
                : `/api/chat/profiles/${encodeURIComponent(profileId)}`,
            {
                method: profileId === null ? 'POST' : 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            },
        );
        const payload = await readJson(response);
        if (!response.ok) {
            throw new Error(payload?.message || 'Не удалось сохранить профиль.');
        }
        profileDialog.close();
        await loadProfiles();
    }, 'Не удалось сохранить профиль.');
});

messageInput.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        chatForm.requestSubmit();
    }
});

providerSelect.addEventListener('change', () => {
    const option = providerSelect.selectedOptions[0];
    modelInput.value = option?.dataset.defaultModel || '';
});

contextStrategySelect.addEventListener('change', async () => {
    if (busy) return;
    await runAction(async () => {
        updateBranchControls();
        if (contextStrategySelect.value === 'BRANCHING') {
            await loadBranches();
            const activeBranchId = branchSelect.value;
            if (activeBranchId) {
                await activateBranch(activeBranchId);
            }
        } else {
            await loadState();
        }
        await loadMemory();
    }, 'Не удалось переключить context strategy.');
});

branchSelect.addEventListener('change', async () => {
    if (busy || !branchSelect.value) return;
    await runAction(async () => {
        await activateBranch(branchSelect.value);
        await loadBranches();
        await loadMemory();
    }, 'Не удалось переключить ветку.');
});

createBranchButton.addEventListener('click', async () => {
    if (busy) return;
    await runAction(async () => {
        const response = await fetch('/api/chat/branches', { method: 'POST' });
        const payload = await readJson(response);
        if (!response.ok || typeof payload?.id !== 'number') {
            throw new Error(payload?.message || 'Не удалось создать ветку.');
        }
        await loadBranches();
        await activateBranch(payload.id);
        await loadMemory();
    }, 'Не удалось создать ветку.');
});

document.querySelectorAll('[data-memory-tab]').forEach((button) => {
    button.addEventListener('click', () => {
        document.querySelectorAll('[data-memory-tab]').forEach((tab) => {
            tab.classList.toggle('active', tab === button);
        });
        document.querySelectorAll('[data-memory-layer]').forEach((layer) => {
            const active = layer.dataset.memoryLayer === button.dataset.memoryTab;
            layer.hidden = !active;
            layer.classList.toggle('active', active);
        });
    });
});

void initialize();

async function initialize() {
    setBusy(true);
    loadingMessage.hidden = true;
    try {
        await loadProviders();
        await loadContextStrategies();
        await loadTasks();
        await loadProfiles();
        await loadState();
        await loadBranches();
        await loadMemory();
    } catch (error) {
        showError(error instanceof Error ? error.message : 'Не удалось загрузить настройки чата.');
    } finally {
        setBusy(false);
        messageInput.focus();
    }
}

async function loadProviders() {
    const response = await fetch('/api/chat/providers');
    const payload = await readJson(response);
    if (!response.ok || !Array.isArray(payload)) {
        throw new Error(payload?.message || 'Не удалось загрузить список providers.');
    }
    providerSelect.replaceChildren();
    payload.forEach((provider) => {
        if (
            typeof provider?.provider !== 'string'
            || typeof provider?.displayName !== 'string'
            || typeof provider?.defaultModel !== 'string'
        ) return;
        const option = document.createElement('option');
        option.value = provider.provider;
        option.textContent = provider.displayName;
        option.dataset.defaultModel = provider.defaultModel;
        providerSelect.append(option);
    });
    if (!providerSelect.options.length) {
        throw new Error('Сервер не настроил ни одного LLM provider.');
    }
    const openAiOption = Array.from(providerSelect.options)
        .find((option) => option.value === 'OPENAI');
    providerSelect.value = (openAiOption || providerSelect.options[0]).value;
    modelInput.value = providerSelect.selectedOptions[0]?.dataset.defaultModel || '';
}

async function loadContextStrategies() {
    const response = await fetch('/api/chat/context-strategies');
    const payload = await readJson(response);
    if (!response.ok || !Array.isArray(payload)) {
        throw new Error(payload?.message || 'Не удалось загрузить context strategies.');
    }
    contextStrategySelect.replaceChildren();
    payload.forEach((strategy) => {
        if (typeof strategy?.type !== 'string' || typeof strategy?.displayName !== 'string') return;
        const option = document.createElement('option');
        option.value = strategy.type;
        option.textContent = strategy.displayName;
        contextStrategySelect.append(option);
    });
    if (!contextStrategySelect.options.length) {
        throw new Error('Сервер не настроил context strategies.');
    }
    const slidingWindow = Array.from(contextStrategySelect.options)
        .find((option) => option.value === 'SLIDING_WINDOW');
    contextStrategySelect.value = (slidingWindow || contextStrategySelect.options[0]).value;
    updateBranchControls();
}

async function loadTasks() {
    const response = await fetch('/api/chat/tasks');
    const payload = await readJson(response);
    if (!response.ok || !Array.isArray(payload)) {
        throw new Error(payload?.message || 'Не удалось загрузить задачи.');
    }
    taskSelect.replaceChildren();
    payload.forEach((task) => {
        if (typeof task?.id !== 'number' || typeof task?.name !== 'string') return;
        const option = document.createElement('option');
        option.value = String(task.id);
        option.textContent = task.status === 'COMPLETED' ? `${task.name} (завершена)` : task.name;
        option.dataset.selected = String(task.selected === true);
        option.dataset.status = task.status;
        taskSelect.append(option);
    });
    const selected = Array.from(taskSelect.options).find((option) => option.dataset.selected === 'true');
    if (!selected) {
        throw new Error('Сервер не выбрал активную Task.');
    }
    taskSelect.value = selected.value;
    activeTaskStatus = selected.dataset.status || 'ACTIVE';
    updateTaskControls();
}

async function loadProfiles() {
    const response = await fetch('/api/chat/profiles');
    const payload = await readJson(response);
    if (!response.ok || !Array.isArray(payload)) {
        throw new Error(payload?.message || 'Не удалось загрузить профили.');
    }
    profiles = payload.filter((profile) =>
        typeof profile?.id === 'number' && typeof profile?.name === 'string',
    );
    profileSelect.replaceChildren();
    if (!profiles.length) {
        const option = document.createElement('option');
        option.value = '';
        option.textContent = 'Профиль не создан';
        profileSelect.append(option);
    } else {
        profiles.forEach((profile) => {
            const option = document.createElement('option');
            option.value = String(profile.id);
            option.textContent = profile.name;
            option.dataset.active = String(profile.active === true);
            profileSelect.append(option);
        });
        const active = profiles.find((profile) => profile.active === true);
        if (active) {
            profileSelect.value = String(active.id);
        } else {
            const option = document.createElement('option');
            option.value = '';
            option.textContent = 'Активный профиль не выбран';
            profileSelect.prepend(option);
            profileSelect.value = '';
        }
    }
    editProfileButton.disabled = busy || !profileSelect.value;
}

function openProfileDialog(profile) {
    editingProfileId = profile?.id ?? null;
    profileDialogTitle.textContent = profile ? 'Редактировать профиль' : 'Новый профиль';
    profileNameInput.value = profile?.name || '';
    profileLanguageSelect.value = profile?.responseLanguage || 'RUSSIAN';
    profileExpertiseSelect.value = profile?.expertiseLevel || 'INTERMEDIATE';
    profileStyleSelect.value = profile?.responseStyle || 'CONCISE';
    profileFormatSelect.value = profile?.responseFormat || 'STRUCTURED';
    profileCustomInstructions.value = profile?.customInstructions || '';
    profileDialog.showModal();
    profileNameInput.focus();
}

async function loadBranches() {
    const response = await fetch('/api/chat/branches');
    const payload = await readJson(response);
    if (!response.ok || !Array.isArray(payload)) {
        throw new Error(payload?.message || 'Не удалось загрузить ветки.');
    }
    branchSelect.replaceChildren();
    payload.forEach((branch) => {
        if (typeof branch?.id !== 'number' || typeof branch?.name !== 'string') return;
        const option = document.createElement('option');
        option.value = String(branch.id);
        option.textContent = branch.name;
        option.dataset.active = String(branch.active === true);
        branchSelect.append(option);
    });
    const activeOption = Array.from(branchSelect.options)
        .find((option) => option.dataset.active === 'true');
    if (activeOption) branchSelect.value = activeOption.value;
    updateBranchControls();
}

async function activateBranch(branchId) {
    const response = await fetch(`/api/chat/branches/${encodeURIComponent(branchId)}/activate`, {
        method: 'POST',
    });
    const payload = await readJson(response);
    if (!response.ok) {
        throw new Error(payload?.message || 'Не удалось активировать ветку.');
    }
    renderChatState(payload);
}

function updateBranchControls() {
    branchControls.hidden = contextStrategySelect.value !== 'BRANCHING';
}

function updateTaskControls() {
    const completed = activeTaskStatus === 'COMPLETED';
    completeTaskButton.disabled = busy || completed;
    messageInput.placeholder = completed
        ? 'Завершённая задача доступна только для просмотра'
        : 'Введите сообщение...';
    messageInput.disabled = busy || completed;
    sendButton.disabled = busy || completed;
}

async function loadState() {
    const response = await fetch('/api/chat/state');
    const payload = await readJson(response);
    if (!response.ok) {
        throw new Error(payload?.message || 'Не удалось восстановить состояние чата.');
    }
    renderChatState(payload);
}

function renderChatState(payload) {
    if (!Array.isArray(payload?.messages)) {
        throw new Error('Сервер вернул некорректное состояние чата.');
    }
    chatHistory.replaceChildren(emptyState);
    emptyState.hidden = false;
    payload.messages.forEach((message) => {
        if (message?.role === 'USER' && typeof message.content === 'string') {
            appendMessage('user', 'Вы', message.content);
        } else if (message?.role === 'ASSISTANT' && typeof message.content === 'string') {
            appendMessage('agent', 'Агент', message.content);
        }
    });
    resetCurrentStats();
    updateConversationStats(payload.conversationUsage);
    statsPanel.hidden = false;
}

async function loadMemory() {
    const strategy = contextStrategySelect.value || 'SLIDING_WINDOW';
    const response = await fetch(`/api/chat/memory?contextStrategy=${encodeURIComponent(strategy)}`);
    const payload = await readJson(response);
    if (!response.ok) {
        throw new Error(payload?.message || 'Не удалось загрузить Memory Inspector.');
    }
    renderMemory(payload);
}

function renderMemory(payload) {
    const shortTerm = Array.isArray(payload?.shortTerm) ? payload.shortTerm : [];
    shortTermMeta.textContent = `Strategy: ${payload?.strategy || '—'} · ${shortTerm.length} messages in effective context`;
    renderMessages(shortTermMemory, shortTerm);
    renderEntries(workingMemory, payload?.working, 'Рабочая память активной Task пуста.');
    renderEntries(longTermMemory, payload?.longTerm, 'Долговременная память пуста.');
    renderLastUpdate(payload?.lastUpdate);
    renderEffectiveContext(payload?.effectiveContext);
}

function renderMessages(container, messages) {
    container.replaceChildren();
    if (!messages.length) {
        container.append(diagnosticEmpty('Effective Short-Term пуст.'));
        return;
    }
    messages.forEach((message) => {
        container.append(diagnosticRow(message.role || '—', message.content || ''));
    });
}

function renderEntries(container, entries, emptyText) {
    container.replaceChildren();
    const values = Array.isArray(entries) ? entries : [];
    if (!values.length) {
        container.append(diagnosticEmpty(emptyText));
        return;
    }
    values.forEach((entry) => container.append(diagnosticRow(entry.key, entry.value)));
}

function renderLastUpdate(update) {
    lastMemoryUpdate.replaceChildren();
    if (!update) {
        lastMemoryUpdate.append(diagnosticEmpty('Изменений пока нет.'));
        return;
    }
    lastMemoryUpdate.append(diagnosticSection('SHORT-TERM'));
    lastMemoryUpdate.append(diagnosticRow('+ USER', update.userMessage || ''));
    renderChanges(lastMemoryUpdate, 'WORKING', update.working);
    renderChanges(lastMemoryUpdate, 'LONG-TERM', update.longTerm);
    if (update.error) {
        lastMemoryUpdate.append(diagnosticRow('Ошибка extractor', update.error));
    }
}

function renderChanges(container, title, changes) {
    container.append(diagnosticSection(title));
    const values = Array.isArray(changes) ? changes : [];
    if (!values.length) {
        container.append(diagnosticEmpty('Без изменений.'));
        return;
    }
    values.forEach((change) => {
        const symbol = change.type === 'ADDED' ? '+' : change.type === 'UPDATED' ? '~' : '-';
        const value = change.type === 'UPDATED'
            ? `${change.oldValue ?? '—'} → ${change.newValue ?? '—'}`
            : change.type === 'DELETED' ? change.oldValue : change.newValue;
        container.append(diagnosticRow(`${symbol} ${change.key}`, value || ''));
    });
}

function renderEffectiveContext(context) {
    effectiveContext.replaceChildren();
    if (!context) {
        effectiveContext.append(diagnosticEmpty('Контекст ещё не формировался.'));
        return;
    }
    effectiveContext.append(diagnosticSection('SYSTEM PROMPT'));
    effectiveContext.append(diagnosticText(context.systemPrompt || ''));
    effectiveContext.append(diagnosticSection('LONG-TERM MEMORY'));
    appendContextEntries(effectiveContext, context.longTermMemory);
    effectiveContext.append(diagnosticSection('USER PROFILE'));
    appendContextProfile(effectiveContext, context.userProfile);
    effectiveContext.append(diagnosticSection('WORKING MEMORY'));
    appendContextEntries(effectiveContext, context.workingMemory);
    effectiveContext.append(diagnosticSection(`EFFECTIVE SHORT-TERM · ${context.strategy}`));
    const shortTerm = Array.isArray(context.shortTerm) ? context.shortTerm : [];
    if (!shortTerm.length) effectiveContext.append(diagnosticEmpty('(empty)'));
    shortTerm.forEach((message) => {
        effectiveContext.append(diagnosticRow(message.role || '—', message.content || ''));
    });
    effectiveContext.append(diagnosticSection('CURRENT USER MESSAGE'));
    effectiveContext.append(diagnosticRow('USER', context.currentUserMessage?.content || ''));
}

function appendContextProfile(container, profile) {
    if (!profile) {
        container.append(diagnosticEmpty('(no active profile)'));
        return;
    }
    container.append(diagnosticRow('Profile', profile.name || '—'));
    container.append(diagnosticRow('Language', profile.responseLanguage || '—'));
    container.append(diagnosticRow('Expertise', profile.expertiseLevel || '—'));
    container.append(diagnosticRow('Style', profile.responseStyle || '—'));
    container.append(diagnosticRow('Format', profile.responseFormat || '—'));
    if (profile.customInstructions) {
        container.append(diagnosticRow('Custom Instructions', profile.customInstructions));
    }
}

function appendContextEntries(container, entries) {
    const values = Array.isArray(entries) ? entries : [];
    if (!values.length) {
        container.append(diagnosticEmpty('(empty)'));
        return;
    }
    values.forEach((entry) => container.append(diagnosticRow(entry.key, entry.value)));
}

function diagnosticRow(label, value) {
    const row = document.createElement('div');
    row.className = 'diagnostic-row';
    const key = document.createElement('strong');
    key.textContent = label;
    const content = document.createElement('span');
    content.textContent = value;
    row.append(key, content);
    return row;
}

function diagnosticSection(title) {
    const heading = document.createElement('h4');
    heading.className = 'diagnostic-section-title';
    heading.textContent = title;
    return heading;
}

function diagnosticEmpty(text) {
    const element = document.createElement('p');
    element.className = 'diagnostic-empty';
    element.textContent = text;
    return element;
}

function diagnosticText(text) {
    const element = document.createElement('pre');
    element.className = 'diagnostic-text';
    element.textContent = text;
    return element;
}

function appendMessage(role, label, content) {
    emptyState.hidden = true;
    const wrapper = document.createElement('article');
    wrapper.className = `message message-${role}`;
    const card = document.createElement('div');
    card.className = 'message-card';
    const author = document.createElement('p');
    author.className = 'message-label';
    author.textContent = label;
    const text = document.createElement('p');
    text.className = 'message-content';
    text.textContent = content;
    card.append(author, text);
    wrapper.append(card);
    chatHistory.append(wrapper);
    chatHistory.scrollTop = chatHistory.scrollHeight;
    return wrapper;
}

function updateStats(payload) {
    const providerOption = Array.from(providerSelect.options)
        .find((option) => option.value === payload.provider);
    document.querySelector('#provider-stat').textContent =
        providerOption?.textContent || valueOrDash(payload.provider);
    document.querySelector('#model-stat').textContent = valueOrDash(payload.model);
    document.querySelector('#current-input-tokens-stat').textContent =
        formatTokenCount(payload.currentUsage?.inputTokens);
    document.querySelector('#current-output-tokens-stat').textContent =
        formatTokenCount(payload.currentUsage?.outputTokens);
    document.querySelector('#current-total-tokens-stat').textContent =
        formatTokenCount(payload.currentUsage?.totalTokens);
    updateConversationStats(payload.conversationUsage);
    const milliseconds = Number(payload.responseTimeMs);
    document.querySelector('#response-time').textContent = Number.isFinite(milliseconds)
        ? `${(milliseconds / 1000).toFixed(2)} сек`
        : '—';
    statsPanel.hidden = false;
}

function updateConversationStats(usage) {
    document.querySelector('#conversation-input-tokens-stat').textContent =
        formatTokenCount(usage?.inputTokens, '0');
    document.querySelector('#conversation-output-tokens-stat').textContent =
        formatTokenCount(usage?.outputTokens, '0');
    document.querySelector('#conversation-total-tokens-stat').textContent =
        formatTokenCount(usage?.totalTokens, '0');
}

function resetCurrentStats() {
    document.querySelector('#provider-stat').textContent = '—';
    document.querySelector('#model-stat').textContent = '—';
    document.querySelector('#current-input-tokens-stat').textContent = '—';
    document.querySelector('#current-output-tokens-stat').textContent = '—';
    document.querySelector('#current-total-tokens-stat').textContent = '—';
    document.querySelector('#response-time').textContent = '—';
}

function formatTokenCount(value, fallback = '—') {
    const number = Number(value);
    return Number.isFinite(number) ? number.toLocaleString('ru-RU') : fallback;
}

function setBusy(value) {
    busy = value;
    providerSelect.disabled = value;
    modelInput.disabled = value;
    contextStrategySelect.disabled = value;
    taskSelect.disabled = value;
    profileSelect.disabled = value;
    createProfileButton.disabled = value;
    editProfileButton.disabled = value || !profileSelect.value;
    createTaskButton.disabled = value;
    branchSelect.disabled = value;
    createBranchButton.disabled = value;
    resetButton.disabled = value;
    clearWorkingButton.disabled = value;
    clearLongTermButton.disabled = value;
    loadingMessage.hidden = !value;
    chatHistory.setAttribute('aria-busy', String(value));
    updateTaskControls();
}

async function runAction(action, fallbackMessage) {
    clearError();
    setBusy(true);
    try {
        await action();
    } catch (error) {
        showError(error instanceof Error ? error.message : fallbackMessage);
    } finally {
        setBusy(false);
        messageInput.focus();
    }
}

async function postNoContent(url, fallbackMessage) {
    const response = await fetch(url, { method: 'POST' });
    if (!response.ok) {
        const payload = await readJson(response);
        throw new Error(payload?.message || fallbackMessage);
    }
}

function showError(message) {
    errorMessage.textContent = message;
    errorMessage.hidden = false;
}

function clearError() {
    errorMessage.textContent = '';
    errorMessage.hidden = true;
}

function restoreEmptyStateIfNeeded() {
    if (!chatHistory.querySelector('.message')) emptyState.hidden = false;
}

function valueOrDash(value) {
    return value === null || value === undefined ? '—' : String(value);
}

async function readJson(response) {
    try {
        return await response.json();
    } catch {
        return null;
    }
}
