#ifndef slic3r_AsyncMeshRaycasterBuilder_hpp_
#define slic3r_AsyncMeshRaycasterBuilder_hpp_

#include "MeshUtils.hpp"
#include "libslic3r/TriangleMesh.hpp"

#include <atomic>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <queue>
#include <thread>
#include <vector>

namespace Slic3r {
namespace GUI {

class AsyncMeshRaycasterBuilder
{
public:
    using BuildCallback = std::function<void(std::unique_ptr<MeshRaycaster>)>;

    struct BuildTask {
        int task_id{ 0 };
        std::shared_ptr<const TriangleMesh> mesh;
        BuildCallback callback;
        int priority{ 0 };

        bool operator<(const BuildTask& other) const { return priority < other.priority; }
    };

    static AsyncMeshRaycasterBuilder& instance();

    void init(int num_threads = 0);
    void shutdown(bool log_messages = true);

    int submit_build_task(
        std::shared_ptr<const TriangleMesh> mesh,
        BuildCallback callback,
        int priority = 0);

    bool cancel_task(int task_id);
    void process_completed_tasks();

    size_t get_pending_tasks_count() const;
    size_t get_active_tasks_count() const;
    bool has_completed_tasks() const;

private:
    AsyncMeshRaycasterBuilder();
    ~AsyncMeshRaycasterBuilder();

    AsyncMeshRaycasterBuilder(const AsyncMeshRaycasterBuilder&) = delete;
    AsyncMeshRaycasterBuilder& operator=(const AsyncMeshRaycasterBuilder&) = delete;

    void worker_thread_func();

    struct CompletedTask {
        int task_id{ 0 };
        std::unique_ptr<MeshRaycaster> raycaster;
        BuildCallback callback;
    };

    std::vector<std::thread> m_workers;
    mutable std::mutex m_queue_mutex;
    std::condition_variable m_cv;
    std::atomic<bool> m_shutdown_flag{ false };

    std::priority_queue<BuildTask> m_pending_tasks;
    std::vector<CompletedTask> m_completed_tasks;
    mutable std::mutex m_completed_mutex;

    std::atomic<int> m_next_task_id{ 1 };
    std::atomic<size_t> m_active_tasks_count{ 0 };
};

} // namespace GUI
} // namespace Slic3r

#endif // slic3r_AsyncMeshRaycasterBuilder_hpp_

