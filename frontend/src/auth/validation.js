export function validateAuth(values, signup) {
  const errors = {}
  if (signup && (!values.name.trim() || values.name.length > 100)) errors.name = '이름을 1~100자로 입력해 주세요.'
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(values.email.trim()) || values.email.length > 255) errors.email = '이메일 형식을 확인해 주세요.'
  if (!values.password.trim()) errors.password = '비밀번호를 입력해 주세요.'
  else if (signup && (values.password.length < 8 || new TextEncoder().encode(values.password).length > 72)) {
    errors.password = '비밀번호는 8자 이상, 72바이트 이하여야 합니다.'
  }
  if (signup && values.confirm !== values.password) errors.confirm = '비밀번호가 일치하지 않습니다.'
  else if (signup && !values.confirm) errors.confirm = '비밀번호를 다시 입력해 주세요.'
  return errors
}
