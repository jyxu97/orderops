import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppLayout } from '../components/AppLayout';
import { NotFoundPage } from './NotFoundPage';
import { CreateOrderPage } from '../features/orders/CreateOrderPage';
import { OrderListPage } from '../features/orders/OrderListPage';
import { OrderDetailPage } from '../features/orders/OrderDetailPage';
import { OperationsPage } from '../features/operations/OperationsPage';
import { FailuresPage } from '../features/operations/FailuresPage';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/orders" replace /> },
      { path: 'order/create', element: <CreateOrderPage /> },
      { path: 'orders', element: <OrderListPage /> },
      { path: 'orders/:orderId', element: <OrderDetailPage /> },
      { path: 'operations', element: <OperationsPage /> },
      { path: 'operations/failures', element: <FailuresPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
