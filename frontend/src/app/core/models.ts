/**
 * Perguntas de segurança padrão da Microsoft (conta local do Windows), em português.
 * O usuário também pode escrever uma pergunta personalizada.
 */
export const MICROSOFT_QUESTIONS = [
  'Qual era o nome do seu primeiro animal de estimação?',
  'Qual é o nome da cidade onde você nasceu?',
  'Qual era o seu apelido de infância?',
  'Qual é o nome da cidade onde seus pais se conheceram?',
  'Qual é o primeiro nome do seu primo mais velho?',
  'Qual é o nome da primeira escola que você frequentou?',
];

export type Role = 'ADMIN' | 'USER';

export interface QuestionAnswer {
  question: string;
  answer: string;
}

export interface Account {
  username: string;
  role: Role;
  securityQuestions: string[];
  passwordChangeRequired: boolean;
  setupRequired: boolean;
}

// ------------------------------------------------------------------ administração

export interface AdminUser {
  id: string;
  username: string;
  role: Role;
  active: boolean;
  createdAt: string;
  lastLoginAt: string | null;
  you: boolean;
}

export interface NewInvite {
  id: string;
  code: string;
  expiresAt: string;
}

export type InviteStatus = 'PENDENTE' | 'USADO' | 'EXPIRADO' | 'REVOGADO';

export interface InviteInfo {
  id: string;
  createdAt: string;
  expiresAt: string;
  usedBy: string | null;
  usedAt: string | null;
  revokedAt: string | null;
  status: InviteStatus;
}
