import React, { useState } from 'react';
import { Navigation } from './components/Navigation';
import { Dashboard } from './components/Dashboard';
import { QuoteGenerator } from './components/QuoteGenerator';
import { CalendarView } from './components/CalendarView';
import { VoiceAssistant } from './components/VoiceAssistant';
import { Appointment, ServiceStatus, Expense } from './types';

// Mock data for initial state
const INITIAL_APPOINTMENTS: Appointment[] = [
  {
    id: '1',
    clientName: 'Roberto Almeida',
    clientPhone: '11999999999',
    clientAddress: 'Rua das Flores, 123 - Centro',
    serviceDate: new Date(new Date().setDate(new Date().getDate() + 1)), // Tomorrow
    startTime: '09:00',
    durationHours: 3,
    services: 'Lavagem Sofá Retrátil + Impermeabilização',
    totalPrice: 450.00,
    status: ServiceStatus.CONFIRMED,
    isPaid: false,
    reminderSent: false
  },
  {
    id: '2',
    clientName: 'Fernanda Costa',
    clientPhone: '11988888888',
    clientAddress: 'Av. Paulista, 1000 - Apt 42',
    serviceDate: new Date(), // Today
    startTime: '14:00',
    durationHours: 2,
    services: 'Higienização Colchão King',
    totalPrice: 280.00,
    status: ServiceStatus.PENDING,
    isPaid: false,
    reminderSent: false
  },
  {
    id: '3',
    clientName: 'Carlos Silva',
    clientPhone: '11977777777',
    clientAddress: 'Rua Augusta, 500',
    serviceDate: new Date(new Date().setDate(new Date().getDate() - 2)), // 2 days ago
    startTime: '10:00',
    durationHours: 2,
    services: 'Sofá 2 Lugares',
    totalPrice: 200.00,
    status: ServiceStatus.COMPLETED,
    isPaid: true,
    reminderSent: true
  }
];

const INITIAL_EXPENSES: Expense[] = [
  { id: '1', description: 'Gasolina', amount: 50.00, date: new Date(), category: 'Combustível' },
  { id: '2', description: 'Detergente Extrator', amount: 85.90, date: new Date(), category: 'Produtos' }
];

const App: React.FC = () => {
  const [currentView, setCurrentView] = useState('dashboard');
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const [appointments, setAppointments] = useState<Appointment[]>(INITIAL_APPOINTMENTS);
  const [expenses, setExpenses] = useState<Expense[]>(INITIAL_EXPENSES);

  const handleAddAppointmentsFromVoice = (newAppts: Appointment[]) => {
      // Sort and merge
      const updated = [...appointments, ...newAppts].sort((a, b) => 
          new Date(a.serviceDate).getTime() - new Date(b.serviceDate).getTime()
      );
      setAppointments(updated);
      
      // Optional: Switch to calendar view to show result
      setCurrentView('calendar');
  };

  const renderView = () => {
    switch (currentView) {
      case 'dashboard':
        return <Dashboard appointments={appointments} setAppointments={setAppointments} expenses={expenses} setExpenses={setExpenses} />;
      case 'quotes':
        return <QuoteGenerator />;
      case 'calendar':
        return <CalendarView appointments={appointments} setAppointments={setAppointments} />;
      default:
        return <Dashboard appointments={appointments} setAppointments={setAppointments} expenses={expenses} setExpenses={setExpenses} />;
    }
  };

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col md:flex-row">
      <Navigation 
        currentView={currentView} 
        setCurrentView={setCurrentView}
        isMobileMenuOpen={isMobileMenuOpen}
        setIsMobileMenuOpen={setIsMobileMenuOpen}
      />
      
      <main className="flex-1 p-4 md:p-8 pt-20 md:pt-8 h-screen overflow-y-auto">
        <div className="max-w-7xl mx-auto">
          {renderView()}
        </div>
      </main>

      <VoiceAssistant onAddAppointments={handleAddAppointmentsFromVoice} />
    </div>
  );
};

export default App;