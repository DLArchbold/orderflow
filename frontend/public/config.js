// Runtime configuration. In development this file is empty and the defaults in src/auth.ts apply.
// In the container, nginx answers /config.js itself with the real values (see nginx.conf.template), so one
// image works behind any hostname, which an ALB name that is only known after deployment requires.
window.ORDERFLOW_CONFIG = window.ORDERFLOW_CONFIG || {};
