const chatForm = document.querySelector('#chat-form');
const chatHistory = document.querySelector('#chat-history');
const emptyState = document.querySelector('#empty-state');
const messageInput = document.querySelector('#message-input');
const providerSelect = document.querySelector('#provider-select');
const modelInput = document.querySelector('#model-input');
const sendButton = document.querySelector('#send-button');
const resetButton = document.querySelector('#reset-button');
const loadingMessage = document.querySelector('#loading-message');
const errorMessage = document.querySelector('#error-message');
const statsPanel = document.querySelector('#stats-panel');

let busy = false;

chatForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (busy) return;

    const message = messageInput.value.trim();
    if (!message) {
        showError('Введите сообщение.');
        messageInput.focus();
        return;
    }
    const provider = providerSelect.value;
    const model = modelInput.value.trim();
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

    clearError();
    const userElement = appendMessage('user', 'Вы', message);
    messageInput.value = '';
    setBusy(true);

    try {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message, provider, model }),
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

    clearError();
    setBusy(true);
    try {
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
    } catch (error) {
        showError(error instanceof Error ? error.message : 'Не удалось сбросить чат.');
    } finally {
        setBusy(false);
        messageInput.focus();
    }
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

void initialize();

async function initialize() {
    setBusy(true);
    loadingMessage.hidden = true;
    try {
        await loadProviders();
        await loadState();
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
        ) {
            return;
        }

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

async function loadState() {
    const response = await fetch('/api/chat/state');
    const payload = await readJson(response);
    if (!response.ok) {
        throw new Error(payload?.message || 'Не удалось восстановить состояние чата.');
    }
    if (!Array.isArray(payload?.messages)) {
        throw new Error('Сервер вернул некорректное состояние чата.');
    }

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
    messageInput.disabled = value;
    sendButton.disabled = value;
    resetButton.disabled = value;
    providerSelect.disabled = value;
    modelInput.disabled = value;
    loadingMessage.hidden = !value;
    chatHistory.setAttribute('aria-busy', String(value));
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
    if (!chatHistory.querySelector('.message')) {
        emptyState.hidden = false;
    }
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
