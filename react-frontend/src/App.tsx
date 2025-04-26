import { BrowserRouter as Router, Routes, Route, Link } from "react-router-dom";
import Home from "./components/Home";
import Callback from "./components/Callback";
import Tokens from "./components/Tokens";

function App() {
  return (
    <Router>
      <div className="min-h-screen bg-gray-100 p-6">
        <div className="max-w-3xl mx-auto bg-white rounded-lg shadow-md p-6">
          <nav className="mb-6">
            <ul className="flex space-x-4 justify-center">
              <li>
                <Link to="/" className="text-blue-600 hover:underline">
                  Login
                </Link>
              </li>
              <li>
                <Link to="/tokens" className="text-red-600 hover:underline">
                  Tokens
                </Link>
              </li>
            </ul>
          </nav>
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/callback" element={<Callback />} />
            <Route path="/tokens" element={<Tokens />} />
          </Routes>
        </div>
      </div>
    </Router>
  );
}

export default App;
