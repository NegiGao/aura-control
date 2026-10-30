(function (root) {
  'use strict';
  const SESSION_MS = 15 * 60 * 1000;
  const budgets = Object.freeze({"calendar":120,"chrome":120,"docs":120,"drive":120,"gemini":120,"gmail":120,"google-maps":120,"photos":120,"sheets":120,"slides":120,"youtube":120});
  const appId = value => value;
  const fresh = () => ({sessionDeadline:null, stage:null});
  function remaining(deadline, now = Date.now()) { return Math.max(0, deadline - now); }
  function phase(deadline, now = Date.now(), warningMs = 60000) {
    if (deadline === null) return 'ready';
    const left = remaining(deadline, now);
    return left === 0 ? 'finished' : left <= warningMs ? 'last-minute' : 'running';
  }
  function format(milliseconds) {
    const seconds = Math.ceil(Math.max(0, milliseconds) / 1000);
    return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
  }
  class Timers {
    constructor(saved = {}) {
      this.devices = Object.create(null);
      for (const [key, value] of Object.entries(saved || {})) {
        if (!key || !value || typeof value !== 'object') continue;
        const sessionDeadline = Number.isFinite(value.sessionDeadline) && value.sessionDeadline > 0 ? value.sessionDeadline : null;
        const stage = value.stage && Object.hasOwn(budgets, value.stage.app) && Number.isFinite(value.stage.deadline) && value.stage.deadline > 0 ? value.stage : null;
        this.devices[key] = {sessionDeadline, stage};
      }
    }
    get(identity) { return identity ? (this.devices[identity] ||= fresh()) : fresh(); }
    start(identity, now = Date.now()) {
      if (identity && this.get(identity).sessionDeadline === null) this.get(identity).sessionDeadline = now + SESSION_MS;
    }
    reset(identity) { if (identity) this.devices[identity] = fresh(); }
    launched(identity, app, now = Date.now()) {
      app = appId(app);
      if (!identity || !Object.hasOwn(budgets, app)) return;
      const timer = this.get(identity);
      if (timer.stage?.app !== app) timer.stage = {app, deadline:now + budgets[app] * 1000};
    }
    closed(identity, app = null) {
      if (identity && (!app || this.get(identity).stage?.app === appId(app))) this.get(identity).stage = null;
    }
    toJSON() { return this.devices; }
  }
  const api = {SESSION_MS, budgets, appId, remaining, phase, format, Timers};
  root.AuraExperience = api;
  if (typeof module !== 'undefined') module.exports = api;
})(globalThis);
