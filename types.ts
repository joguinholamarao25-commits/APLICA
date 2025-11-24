export enum ServiceCategory {
  SOFA = 'Sofá',
  RUG = 'Tapete',
  MATTRESS = 'Colchão',
  CHAIR = 'Cadeiras',
  CAR_INTERIOR = 'Estofado Automotivo',
  OTHER = 'Outros'
}

export enum ServiceStatus {
  PENDING = 'Pendente',
  CONFIRMED = 'Confirmado',
  COMPLETED = 'Concluído',
  CANCELLED = 'Cancelado'
}

export interface Client {
  id: string;
  name: string;
  phone: string;
  address?: string;
}

export interface QuoteItem {
  category: ServiceCategory;
  description: string; // e.g., "3 Lugares, Retrátil"
  condition: string; // e.g., "Manchas de vinho, poeira"
  price: number;
}

export interface Quote {
  id: string;
  clientName: string;
  clientPhone: string;
  items: QuoteItem[];
  totalPrice: number;
  aiMessage: string; // The generated text for WhatsApp
  createdAt: Date;
}

export interface Appointment {
  id: string;
  clientName: string;
  clientPhone: string;
  clientAddress: string;
  serviceDate: Date;
  startTime: string; // "14:00"
  durationHours: number;
  services: string; // Summary of services
  totalPrice: number;
  status: ServiceStatus;
  isPaid: boolean;
  reminderSent?: boolean; // Novo campo para controle de lembretes
}

export interface Expense {
  id: string;
  description: string;
  amount: number;
  date: Date;
  category: 'Combustível' | 'Alimentação' | 'Produtos' | 'Manutenção' | 'Outros';
}