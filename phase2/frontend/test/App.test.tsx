import { render, screen } from '@testing-library/react';
import { App } from '../App';

describe('App', () => {
  it('renders the heading and an empty order list', () => {
    render(<App />);
    expect(screen.getByRole('heading', { name: 'OrderFlow' })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'orders' })).toBeEmptyDOMElement();
  });
});
