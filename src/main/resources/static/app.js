const chatForm = document.querySelector('#chat-form');
const chatHistory = document.querySelector('#chat-history');
const emptyState = document.querySelector('#empty-state');
const messageInput = document.querySelector('#message-input');
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

    clearError();
    const userElement = appendMessage('user', 'Вы', message);
    messageInput.value = '';
    setBusy(true);

    try {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message }),
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
        statsPanel.hidden = true;
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
void loadHistory();

async function loadHistory() {
    setBusy(true);
    loadingMessage.hidden = true;
    try {
        const response = await fetch('/api/chat/history');
        const payload = await readJson(response);
        if (!response.ok) {
            throw new Error(payload?.message || 'Не удалось восстановить историю чата.');
        }
        if (!Array.isArray(payload)) {
            throw new Error('Сервер вернул некорректную историю чата.');
        }

        payload.forEach((message) => {
            if (message?.role === 'USER' && typeof message.content === 'string') {
                appendMessage('user', 'Вы', message.content);
            } else if (message?.role === 'ASSISTANT' && typeof message.content === 'string') {
                appendMessage('agent', 'Агент', message.content);
            }
        });
    } catch (error) {
        showError(error instanceof Error ? error.message : 'Не удалось восстановить историю чата.');
    } finally {
        setBusy(false);
        messageInput.focus();
    }
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
    document.querySelector('#model-stat').textContent = valueOrDash(payload.model);
    document.querySelector('#input-tokens-stat').textContent = valueOrDash(payload.inputTokens);
    document.querySelector('#output-tokens-stat').textContent = valueOrDash(payload.outputTokens);
    document.querySelector('#total-tokens-stat').textContent = valueOrDash(payload.totalTokens);

    const milliseconds = Number(payload.responseTimeMs);
    document.querySelector('#response-time').textContent = Number.isFinite(milliseconds)
        ? `${(milliseconds / 1000).toFixed(2)} сек`
        : '—';
    statsPanel.hidden = false;
}

function setBusy(value) {
    busy = value;
    messageInput.disabled = value;
    sendButton.disabled = value;
    resetButton.disabled = value;
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
