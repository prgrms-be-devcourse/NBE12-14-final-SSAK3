import React from 'react'

const paths = {
  home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z',
  plus: 'M12 5v14M5 12h14',
  history: 'M3 5v5h5M3.8 9a8.5 8.5 0 1 1-.1 6M12 7v5l3.5 2',
  users: 'M2.5 20v-2a5 5 0 0 1 5-5h3a5 5 0 0 1 5 5v2M13 6a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM16 3a4 4 0 0 1 0 7.5M18 13a5 5 0 0 1 3.5 4.8V20',
  menu: 'M3 6h18M3 12h18M3 18h18',
  x: 'm6 6 12 12M6 18 18 6',
  'arrow-right': 'M4 12h15m-6-6 6 6-6 6',
  'arrow-up-right': 'M6 18 18 6M7 6h11v11',
  mic: 'M9 6a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0ZM5 11v1a7 7 0 0 0 14 0v-1M12 19v3m-4 0h8',
  activity: 'M2 12h4l3-8 5 16 3-8h5',
  chart: 'M3 3v17h18M7.5 15v-4m5 4V6m5 9v-6',
  message: 'M4 3.5h16a1.5 1.5 0 0 1 1.5 1.5v11a1.5 1.5 0 0 1-1.5 1.5H9l-6.5 4V5A1.5 1.5 0 0 1 4 3.5ZM7 8h10M7 12h7',
  'chevron-right': 'm9 5 7 7-7 7',
  calendar: 'M4 5h16v16H4ZM4 10h16M8 3v4m8-4v4',
  clock: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM12 7v5l3.5 2',
  radio: 'M13.5 12a1.5 1.5 0 1 1-3 0 1.5 1.5 0 0 1 3 0ZM7.8 7.8a6 6 0 0 0 0 8.4m8.4-8.4a6 6 0 0 1 0 8.4M4.6 4.6a10.4 10.4 0 0 0 0 14.8m14.8-14.8a10.4 10.4 0 0 1 0 14.8',
  qr: 'M3 3h7v7H3ZM14 3h7v7h-7ZM3 14h7v7H3ZM14 14h3v3h4m-7 3v1h3m4-1v1',
  'log-out': 'M9 4H4v16h5m-1-8h13m-5-5 5 5-5 5',
  file: 'M5 3h9l5 5v13H5ZM14 3v6h5M8 13h8m-8 4h6',
  check: 'm4 12 5 5L20 6',
  mail: 'M3 5h18v14H3ZM3 6l9 7 9-7',
  lock: 'M5 10h14v11H5ZM8 10V7a4 4 0 1 1 8 0v3M12 14v3',
  eye: 'M1.5 12s3.5-7 10.5-7 10.5 7 10.5 7-3.5 7-10.5 7S1.5 12 1.5 12ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  'eye-off': 'M3 3l18 18M9 5.5c1-.4 2-.5 3-.5 7 0 10.5 7 10.5 7s-1.2 2.1-3.4 4M6.1 6.8C3.3 9 1.5 12 1.5 12s3.5 7 10.5 7c1.5 0 2.9-.3 4.1-.8',
  info: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-.5v5m0-9v.3',
}

export function Icon({ name, size = 20, className }) {
  return <svg aria-hidden="true" focusable="false" className={className} width={size} height={size}
    viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    <path d={paths[name]} />
  </svg>
}
